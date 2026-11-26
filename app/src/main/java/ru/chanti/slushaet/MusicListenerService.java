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
    // Media callbacks can disappear while a player is still running.
    // Poll as a fallback while Android permits this listener to run.
    private final Runnable watchdog=new Runnable(){
        @Override public void run(){
            if(!connected)return;
            try{
                if(TrackStore.active(MusicListenerService.this))refresh();
            }catch(RuntimeException error){
                android.util.Log.w("MusicListener","Media refresh failed",error);
                TrackStore.lost(MusicListenerService.this);
            }finally{
                if(connected)handler.postDelayed(this,5000);
            }
        }
    };

    private final MediaController.Callback onPlayback=new MediaController.Callback(){
        @Override public void onMetadataChanged(MediaMetadata metadata){MediaScan.read(MusicListenerService.this);}
        @Override public void onPlaybackStateChanged(PlaybackState state){MediaScan.read(MusicListenerService.this);}
        @Override public void onSessionDestroyed(){handler.post(()->refresh());}
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions=(list)->refresh();

    private ComponentName component(){return new ComponentName(this,MusicListenerService.class);}

    @Override public void onListenerConnected(){
        super.onListenerConnected();
        TrackStore.recoverOutbox(this);
        connected=true;
        manager=(MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
        if(manager!=null){
            try{manager.addOnActiveSessionsChangedListener(onSessions,component());}
            catch(SecurityException ignored){}
        }
        refresh();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog,5000);
    }
    @Override public void onNotificationPosted(StatusBarNotification item){
        if(item!=null&&MediaScan.supported(item.getPackageName())){
            if(VideoStore.isVideo(item.getPackageName())){
                VideoStore.onMediaNotification(this,item.getPackageName(),item.getNotification());
            }
            refresh();
        }
    }
    @Override public void onNotificationRemoved(StatusBarNotification item){
        if(item!=null&&MediaScan.supported(item.getPackageName()))refresh();
    }
    private void refresh(){
        if(!connected)return;
        if(manager!=null){
            try{
                List<MediaController> current=manager.getActiveSessions(component());
                // Retain callbacks for live tokens instead of unregistering on each scan.
                for(int i=observed.size()-1;i>=0;i--){
                    MediaController old=observed.get(i);
                    boolean found=false;
                    for(MediaController c:current){
                        if(old.getSessionToken().equals(c.getSessionToken())){
                            found=true;break;
                        }
                    }
                    if(!found){
                        try{old.unregisterCallback(onPlayback);}catch(RuntimeException ignored){}
                        observed.remove(i);
                    }
                }
                for(MediaController c:current){
                    if(!MediaScan.supported(c.getPackageName()))continue;
                    boolean found=false;
                    for(MediaController old:observed){
                        if(old.getSessionToken().equals(c.getSessionToken())){
                            found=true;break;
                        }
                    }
                    if(!found){
                        c.registerCallback(onPlayback,handler);
                        observed.add(c);
                    }
                }
            }catch(RuntimeException error){
                android.util.Log.w("MusicListener","Media session refresh failed",error);
            }
        }
        MediaScan.read(this);
    }
    @Override public void onDestroy(){
        handler.removeCallbacks(watchdog);
        connected=false;
        if(manager!=null){
            try{manager.removeOnActiveSessionsChangedListener(onSessions);}catch(RuntimeException ignored){}
        }
        for(MediaController c:observed){
            try{c.unregisterCallback(onPlayback);}catch(RuntimeException ignored){}
        }
        observed.clear();
        TrackStore.lost(this);
        super.onDestroy();
    }
    @Override public void onListenerDisconnected(){
        connected=false;
        handler.removeCallbacks(watchdog);
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
