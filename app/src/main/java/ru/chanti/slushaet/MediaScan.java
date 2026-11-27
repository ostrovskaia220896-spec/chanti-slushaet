package ru.chanti.slushaet;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import java.util.List;

/** Reads only supported media sessions and Yandex Music transport notifications. */
final class MediaScan {
    private MediaScan(){}
    private static Hint hint;
    private static final MusicSourceRules sources=new MusicSourceRules();

    static boolean yandex(String name){
        return "ru.yandex.music".equals(name)||"com.yandex.music".equals(name);
    }
    static boolean supported(String name){return yandex(name)||VideoStore.isVideo(name);}
    private static String clean(CharSequence s){return s==null?"":s.toString().trim();}
    private static String title(MediaMetadata m){
        if(m==null)return "";
        String s=clean(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        return s.isEmpty()?clean(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)):s;
    }
    private static String artist(MediaMetadata m){
        if(m==null)return "";
        String s=clean(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        return s.isEmpty()?clean(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)):s;
    }
    private static boolean generic(String s){
        return s.isEmpty()||"Яндекс Музыка".equalsIgnoreCase(s)
            ||"Яндекс.Музыка".equalsIgnoreCase(s)||"Yandex Music".equalsIgnoreCase(s)
            ||"Музыка".equalsIgnoreCase(s)||"Unknown".equalsIgnoreCase(s);
    }
    private static final class Hint {
        final String pkg,title,artist;
        final MediaSession.Token token;
        final long posted;
        Hint(String p,String t,String a,MediaSession.Token k,long at){
            pkg=p;title=t;artist=a;token=k;posted=at;
        }
    }
    private static Hint extract(StatusBarNotification item){
        if(item==null||!yandex(item.getPackageName()))return null;
        Notification n=item.getNotification();
        if(n==null||n.extras==null)return null;
        Bundle b=n.extras;
        Object raw=b.get(Notification.EXTRA_MEDIA_SESSION);
        MediaSession.Token token=raw instanceof MediaSession.Token?(MediaSession.Token)raw:null;
        if(token==null&&!Notification.CATEGORY_TRANSPORT.equals(n.category))return null;
        String t=clean(b.getCharSequence(Notification.EXTRA_TITLE));
        String a=clean(b.getCharSequence(Notification.EXTRA_TEXT));
        if(generic(t))t="";
        if(generic(a))a="";
        if(token==null&&t.isEmpty())return null;
        return new Hint(item.getPackageName(),t,a,token,item.getPostTime());
    }

    static synchronized void updateNotifications(StatusBarNotification[] items){
        Hint best=null;
        if(items!=null)for(StatusBarNotification item:items){
            try{
                Hint h=extract(item);
                if(h!=null&&(best==null||h.posted>best.posted))best=h;
            }catch(RuntimeException error){
                android.util.Log.w("MediaScan","Invalid media notification",error);
            }
        }
        hint=best;
    }
    static synchronized void clearNotifications(){hint=null;sources.reset();}
    static synchronized void resetSources(){sources.reset();}

    static synchronized void read(Context ctx){
        if(!TrackStore.active(ctx)){sources.reset();return;}
        MediaSessionManager manager=(MediaSessionManager)ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if(manager==null){TrackStore.lost(ctx);return;}
        try{
            List<MediaController> all=manager.getActiveSessions(
                new ComponentName(ctx,MusicListenerService.class));
            MediaController music=null;
            long bestScore=Long.MIN_VALUE;
            for(MediaController c:all){
                String pkg=c.getPackageName();
                // Never inspect metadata belonging to unrelated apps.
                if(!supported(pkg))continue;
                MediaMetadata m=c.getMetadata();
                PlaybackState ps=c.getPlaybackState();
                boolean playing=ps!=null&&ps.getState()==PlaybackState.STATE_PLAYING;
                if(VideoStore.isVideo(pkg)){
                    VideoStore.observed(ctx,pkg,title(m),playing);
                    continue;
                }
                if(!yandex(pkg))continue;
                // Restore the older selector's primary rule: a real playing session
                // outranks a paused notification token. Invalid sessions are not candidates.
                if(ps==null||ps.getState()==PlaybackState.STATE_NONE||
                    ps.getState()==PlaybackState.STATE_ERROR)continue;
                String mt=title(m);
                boolean hasTitle=!generic(mt);
                boolean notificationTitle=hint!=null&&hint.token!=null&&
                    hint.token.equals(c.getSessionToken())&&!generic(hint.title);
                if(!hasTitle&&!notificationTitle)continue;
                long score=Math.max(0L,ps.getLastPositionUpdateTime());
                if(playing)score+=1L<<52;
                if(hasTitle)score+=1L<<50;
                if(notificationTitle)score+=1L<<49;
                if(music==null||score>bestScore){music=c;bestScore=score;}
            }
            // A media notification can point at a controller absent from the active list.
            if(hint!=null&&hint.token!=null&&music==null){
                try{
                    MediaController candidate=new MediaController(ctx,hint.token);
                    PlaybackState candidateState=candidate.getPlaybackState();
                    if(yandex(candidate.getPackageName())&&candidate.getPackageName().equals(hint.pkg)
                        &&candidateState!=null&&candidateState.getState()!=PlaybackState.STATE_NONE
                        &&candidateState.getState()!=PlaybackState.STATE_ERROR)music=candidate;
                }catch(RuntimeException ignored){}
            }
            if(music==null){TrackStore.lost(ctx);return;}
            MediaMetadata m=music.getMetadata();
            PlaybackState state=music.getPlaybackState();
            if(state==null||state.getState()==PlaybackState.STATE_NONE||
                    state.getState()==PlaybackState.STATE_ERROR){
                TrackStore.lost(ctx);return;
            }
            String mt=title(m),ma=artist(m);
            if(generic(mt))mt="";
            Hint h=hint;
            boolean compatible=h!=null&&h.pkg.equals(music.getPackageName())&&
                (h.token==null||h.token.equals(music.getSessionToken()));
            String nt=compatible?h.title:"",na=compatible?h.artist:"";
            boolean useNotification=sources.useNotification(mt,ma,nt,na);
            String t=useNotification?nt:mt;
            String a=useNotification?na:ma;
            if(t.isEmpty()){TrackStore.lost(ctx);return;}
            // With matching titles prefer real session artist; avoid importing generic
            // notification text when session metadata already provides it.
            if(t.equals(mt)&&!ma.isEmpty()&&(!useNotification||na.isEmpty()||ma.equals(na)))a=ma;
            // Do not borrow ratings when the two sources disagree on track identity.
            Rating rating=m!=null&&t.equals(mt)&&a.equals(ma)?
                m.getRating(MediaMetadata.METADATA_KEY_USER_RATING):null;
            TrackStore.onMusic(ctx,t,a,state.getState()==PlaybackState.STATE_PLAYING,rating);
        }catch(SecurityException error){
            TrackStore.lost(ctx);
            android.util.Log.w("MediaScan","Notification listener access unavailable",error);
        }catch(RuntimeException error){
            TrackStore.lost(ctx);
            android.util.Log.w("MediaScan","Media metadata unavailable",error);
        }
    }
}
