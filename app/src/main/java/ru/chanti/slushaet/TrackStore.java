package ru.chanti.slushaet;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.Rating;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** On-device session journal. No account access, network, or content from other apps. */
public final class TrackStore {
    private static final String PREF = "chanti_session_v03";
    private TrackStore() {}
    static final class Snapshot {
        final String title, artist;
        final boolean playing, active;
        final long heardMs;
        Snapshot(String t,String a,boolean p,boolean on,long ms){title=t;artist=a;playing=p;active=on;heardMs=ms;}
    }
    private static SharedPreferences p(Context c){
        return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    static synchronized boolean active(Context c){return p(c).getBoolean("active",false);}
    static synchronized Snapshot snapshot(Context c){
        SharedPreferences a=p(c);boolean playing=a.getBoolean("playing",false);
        long heard=a.getLong("heard",0),since=a.getLong("since",0);
        if(playing&&since>0)heard+=Math.max(0,System.currentTimeMillis()-since);
        return new Snapshot(a.getString("title",""),a.getString("artist",""),playing,a.getBoolean("active",false),heard);
    }
    private static long heard(SharedPreferences a){
        long result=a.getLong("heard",0),since=a.getLong("since",0);
        if(a.getBoolean("playing",false)&&since>0)result+=Math.max(0,System.currentTimeMillis()-since);
        return result;
    }
    private static void add(Context context,SharedPreferences a,String kind,long ms,String note){
        ensureOutboxMigration(context);
        try{
            JSONObject e=new JSONObject();
            e.put("eventId",MusicEventIds.newEventId());
            e.put("requestId",MusicEventIds.requestId(e.getString("eventId")));
            e.put("kind",kind);e.put("at",System.currentTimeMillis());
            e.put("title",a.getString("title",""));e.put("artist",a.getString("artist",""));
            e.put("ms",ms);e.put("note",note);
            try{
                MusicEventOutbox.enqueue(context,e);
                a.edit().putInt("outbox_pending",MusicEventOutbox.pendingCount(context))
                    .remove("outbox_error").apply();
            }catch(RuntimeException storageError){
                a.edit().putString("outbox_error",storageError.getClass().getSimpleName()).apply();
                android.util.Log.e("TrackStore","Could not persist music outbox event",storageError);
            }
            JSONArray old=new JSONArray(a.getString("events","[]")),next=new JSONArray();
            next.put(e);
            for(int i=0;i<old.length()&&i<499;i++)next.put(old.get(i));
            a.edit().putString("events",next.toString()).apply();
        }catch(Exception error){
            android.util.Log.e("TrackStore","Could not record music event",error);
        }
    }
    static synchronized void start(Context c){
        SharedPreferences a=p(c);if(a.getBoolean("active",false))return;
        a.edit().putBoolean("active",true).putString("title","").putString("artist","")
            .putLong("heard",0).putLong("since",0).putBoolean("playing",false)
            .putString("rating","").apply();
        add(c,a,"session_start",0,"");
    }
    private static void endSong(Context c,SharedPreferences a,boolean changed){
        if(a.getString("title","").isEmpty())return;
        long ms=heard(a);
        add(c,a,changed?"track_change":"track_end",ms,
            TrackRules.switchNote(ms, changed));
    }
    static synchronized void stop(Context c){
        SharedPreferences a=p(c);if(!a.getBoolean("active",false))return;
        endSong(c,a,false);
        a.edit().putBoolean("active",false).putBoolean("playing",false).putLong("since",0).apply();
        add(c,a,"session_stop",0,"");
    }
    static synchronized void onMusic(Context c,String title,String artist,boolean playing,Rating rating){
        SharedPreferences a=p(c);
        if(!a.getBoolean("active",false)||title==null||title.trim().isEmpty())return;
        title=title.trim();artist=artist==null?"":artist.trim();
        boolean different=TrackRules.changed(
            a.getString("title",""), a.getString("artist",""), title, artist);
        if(different){
            endSong(c,a,true);
            a.edit().putString("title",title).putString("artist",artist)
                .putLong("heard",0).putLong("since",playing?System.currentTimeMillis():0)
                .putBoolean("playing",playing).putString("rating","").apply();
            add(c,a,"track_start",0,playing?"играет":"пауза");
        }else if(a.getBoolean("playing",false)!=playing){
            long ms=heard(a);
            a.edit().putLong("heard",ms).putLong("since",playing?System.currentTimeMillis():0)
                .putBoolean("playing",playing).apply();
            add(c,a,playing?"resume":"pause",ms,"");
        }
        if(rating!=null&&rating.isRated()){
            String state="";
            if(rating.getRatingStyle()==Rating.RATING_HEART)state=rating.hasHeart()?"liked":"unliked";
            if(rating.getRatingStyle()==Rating.RATING_THUMB_UP_DOWN)state=rating.isThumbUp()?"liked":"unliked";
            if(!state.isEmpty()&&!state.equals(a.getString("rating",""))){
                String previous = a.getString("rating","");
                a.edit().putString("rating",state).apply();
                add(c,a,"rating",heard(a),TrackRules.ratingNote(previous,state));
            }
        }
    }
    static synchronized void lost(Context c){
        SharedPreferences a=p(c);
        if(!a.getBoolean("active",false)||!a.getBoolean("playing",false))return;
        long ms=heard(a);
        a.edit().putBoolean("playing",false).putLong("heard",ms).putLong("since",0).apply();
        add(c,a,"pause",ms,"медиасессия стала недоступна");
    }
    static synchronized String report(Context c){
        JSONArray ev;
        try{ev=new JSONArray(p(c).getString("events","[]"));}catch(Exception e){ev=new JSONArray();}
        if(ev.length()==0)return "Песен пока нет.";
        StringBuilder s=new StringBuilder("Музыкальный дневник для обсуждения с Чанти.\n");
        s.append("Короткое прослушивание не обязательно означает, что песня не понравилась. ");
        s.append("Лайк подтверждается только если Яндекс Музыка передаёт рейтинг.\n\n");
        DateFormat fmt=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
        for(int i=ev.length()-1;i>=0;i--){
            JSONObject e=ev.optJSONObject(i);if(e==null)continue;
            String kind=e.optString("kind"),label;
            switch(kind){
                case "track_start":label="▶";break;
                case "track_change":label="⏭";break;
                case "track_end":label="■";break;
                case "pause":label="⏸";break;
                case "resume":label="▷";break;
                case "rating":
                    String note=e.optString("note");
                    label=note.contains("лайк снят")||note.contains("без лайка")||
                          note.contains("нет лайка")?"♡":"♥";
                    break;
                case "session_start":label="НАЧАЛО";break;
                case "session_stop":label="КОНЕЦ";break;
                default:label=kind;
            }
            s.append(fmt.format(new Date(e.optLong("at")))).append(" ").append(label);
            String t=e.optString("title");
            if(!t.isEmpty())s.append(" «").append(t).append("» — ").append(e.optString("artist"));
            if(kind.equals("track_change")||kind.equals("track_end"))
                s.append(" (").append(Math.round(e.optLong("ms")/1000.0)).append(" с)");
            if(!e.optString("note").isEmpty())s.append(" · ").append(e.optString("note"));
            s.append("\n");
        }
        return s.toString();
    }
    static synchronized int outboxPending(Context c){
        return p(c).getInt("outbox_pending",0);
    }
    static synchronized String outboxError(Context c){
        return p(c).getString("outbox_error","");
    }
    /**
     * One-time migration of the last locally displayed events into the durable outbox.
     * IDs are written to SharedPreferences before SQLite insertion so a retry is idempotent.
     */
    private static synchronized void ensureOutboxMigration(Context c){
        SharedPreferences a=p(c);
        if(a.getBoolean("outbox_migrated_v1",false))return;
        JSONArray old;
        try{old=new JSONArray(a.getString("events","[]"));}
        catch(Exception malformed){
            a.edit().putString("outbox_error","Не удалось прочитать старый журнал").apply();
            return;
        }
        JSONArray normalized=new JSONArray();
        try{
            for(int i=0;i<old.length();i++){
                JSONObject event=old.optJSONObject(i);
                if(event==null)continue;
                String eventId=event.optString("eventId","");
                if(eventId.isEmpty()){
                    eventId=MusicEventIds.newEventId();
                    event.put("eventId",eventId);
                }
                String requestId=event.optString("requestId","");
                if(!requestId.matches("q_[a-f0-9]{64}"))
                    event.put("requestId",MusicEventIds.requestId(eventId));
                normalized.put(event);
            }
            if(!a.edit().putString("events",normalized.toString()).commit())
                throw new IllegalStateException("Не удалось сохранить ID старых событий");
            MusicEventOutbox.enqueueBatch(c,normalized);
            a.edit().putBoolean("outbox_migrated_v1",true)
                .putInt("outbox_pending",MusicEventOutbox.pendingCount(c))
                .remove("outbox_error").apply();
        }catch(Exception error){
            a.edit().putString("outbox_error","Миграция журнала: "+
                error.getClass().getSimpleName()).apply();
            android.util.Log.e("TrackStore","Could not migrate old music events",error);
        }
    }
    static synchronized void clear(Context c){p(c).edit().remove("events").apply();}
}
