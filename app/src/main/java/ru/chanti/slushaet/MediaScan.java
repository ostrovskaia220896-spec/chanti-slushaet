package ru.chanti.slushaet;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.List;

/** Reads ONLY Yandex Music MediaSession metadata, when a session is active. */
final class MediaScan {
    private MediaScan() {}
    static boolean yandex(String s){
        return s!=null&&(s.equals("ru.yandex.music")||s.equals("com.yandex.music")
            ||s.contains("yandex.music"));
    }
    private static String trackTitle(MediaMetadata m) {
        if(m==null)return "";
        String title=m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if(title==null||title.trim().isEmpty())
            title=m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return title==null?"":title.trim();
    }
    static void read(Context context){
        if(!TrackStore.active(context))return;
        MediaSessionManager mgr=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if(mgr==null)return;
        try{
            ComponentName listener=new ComponentName(context,MusicListenerService.class);
            List<MediaController> sessions=mgr.getActiveSessions(listener);
            MediaController selected=null;
            for(MediaController c:sessions){
                if(!yandex(c.getPackageName())||c.getMetadata()==null)continue;
                MediaMetadata m=c.getMetadata();
                String title=trackTitle(m);
                if(title.isEmpty())continue;
                PlaybackState ps=c.getPlaybackState();
                if(selected==null)selected=c;
                if(ps!=null&&ps.getState()==PlaybackState.STATE_PLAYING){selected=c;break;}
            }
            if(selected==null){TrackStore.lost(context);return;}
            MediaMetadata m=selected.getMetadata();
            String title=trackTitle(m);
            String artist=m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if(artist==null||artist.trim().isEmpty())
                artist=m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            PlaybackState ps=selected.getPlaybackState();
            boolean playing=ps!=null&&ps.getState()==PlaybackState.STATE_PLAYING;
            Rating rating=m.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
            TrackStore.onMusic(context,title,artist,playing,rating);
        }catch(SecurityException ignored){
            // MediaSessionManager requires Android Notification Listener access.
        }catch(RuntimeException ignored){}
    }
}
