package ru.chanti.slushaet;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.List;

/** Observes Yandex Music and known video players via Android MediaSession metadata. */
final class MediaScan {
    private MediaScan(){}
    static boolean yandex(String name) {
        return name!=null&&(name.equals("ru.yandex.music")
            ||name.equals("com.yandex.music")||name.contains("yandex.music"));
    }
    static boolean supported(String name){return yandex(name)||VideoStore.isVideo(name);}
    private static String title(MediaMetadata m){
        if(m==null)return "";
        String s=m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if(s==null||s.trim().isEmpty())s=m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return s==null?"":s.trim();
    }
    static void read(Context ctx){
        if(!TrackStore.active(ctx))return;
        MediaSessionManager manager=(MediaSessionManager)ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if(manager==null){TrackStore.lost(ctx);return;}
        try{
            List<MediaController> all=manager.getActiveSessions(
                new ComponentName(ctx,MusicListenerService.class));
            MediaController music=null;
            for(MediaController c:all){
                String pkg=c.getPackageName();
                MediaMetadata m=c.getMetadata();
                PlaybackState ps=c.getPlaybackState();
                boolean playing=ps!=null&&ps.getState()==PlaybackState.STATE_PLAYING;
                if(VideoStore.isVideo(pkg)){
                    VideoStore.observed(ctx,pkg,title(m),playing);
                }
                if(yandex(pkg)&&m!=null&&!title(m).isEmpty()){
                    if(music==null)music=c;
                    if(playing)music=c;
                }
            }
            if(music==null){TrackStore.lost(ctx);return;}
            MediaMetadata m=music.getMetadata();
            if(m==null||title(m).isEmpty()){TrackStore.lost(ctx);return;}
            String artist=m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if(artist==null||artist.trim().isEmpty())
                artist=m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            PlaybackState state=music.getPlaybackState();
            Rating rating=m.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
            TrackStore.onMusic(ctx,title(m),artist,
                state!=null&&state.getState()==PlaybackState.STATE_PLAYING,rating);
        }catch(SecurityException error){
            TrackStore.lost(ctx);
            android.util.Log.w("MediaScan","Notification listener access unavailable",error);
        }catch(RuntimeException error){
            TrackStore.lost(ctx);
            android.util.Log.w("MediaScan","Media metadata unavailable",error);
        }
    }
}
