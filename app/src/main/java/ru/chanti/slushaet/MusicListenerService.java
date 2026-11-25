package ru.chanti.slushaet;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.MediaMetadata;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.List;

/** A system-bound listener. It discards every non-Yandex notification without reading it. */
public final class MusicListenerService extends NotificationListenerService {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<MediaController> observed=new ArrayList<>();
    private MediaSessionManager manager;
    private boolean connected=false;

    private final MediaController.Callback onPlayback=new MediaController.Callback(){
        @Override public void onMetadataChanged(MediaMetadata metadata){MediaScan.read(MusicListenerService.this);}
        @Override public void onPlaybackStateChanged(PlaybackState state){MediaScan.read(MusicListenerService.this);}
        @Override public void onSessionDestroyed(){handler.post(()->refresh());}
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions=(list)->refresh();

    private ComponentName component(){return new ComponentName(this,MusicListenerService.class);}

    @Override public void onListenerConnected(){
        super.onListenerConnected();
        connected=true;
        manager=(MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
        if(manager!=null){
            try{manager.addOnActiveSessionsChangedListener(onSessions,component());}
            catch(SecurityException ignored){}
        }
        refresh();
    }
    @Override public void onNotificationPosted(StatusBarNotification item){
        if(item!=null&&MediaScan.yandex(item.getPackageName()))refresh();
    }
    @Override public void onNotificationRemoved(StatusBarNotification item){
        if(item!=null&&MediaScan.yandex(item.getPackageName()))refresh();
    }
    private void refresh(){
        if(!connected)return;
        for(MediaController c:observed){
            try{c.unregisterCallback(onPlayback);}catch(RuntimeException ignored){}
        }
        observed.clear();
        if(manager!=null){
            try{
                for(MediaController c:manager.getActiveSessions(component())){
                    if(!MediaScan.yandex(c.getPackageName()))continue;
                    observed.add(c);
                    c.registerCallback(onPlayback,handler);
                }
            }catch(SecurityException ignored){}
        }
        MediaScan.read(this);
    }
    @Override public void onListenerDisconnected(){
        connected=false;
        if(manager!=null){
            try{manager.removeOnActiveSessionsChangedListener(onSessions);}catch(RuntimeException ignored){}
        }
        for(MediaController c:observed){
            try{c.unregisterCallback(onPlayback);}catch(RuntimeException ignored){}
        }
        observed.clear();
        TrackStore.lost(this);
        super.onListenerDisconnected();
    }
}
