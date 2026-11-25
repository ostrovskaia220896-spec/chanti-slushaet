package ru.chanti.slushaet;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** Records only media metadata from selected video players; never video frames or audio. */
final class VideoStore {
    private static SharedPreferences p(Context ctx){
        return ctx.getApplicationContext().getSharedPreferences("video_observation_v05",Context.MODE_PRIVATE);
    }
    static boolean isVideo(String pkg){
        if(pkg==null)return false;
        return pkg.equals("com.google.android.youtube")
            ||pkg.startsWith("ru.kinopoisk")||pkg.startsWith("ru.yandex.kinopoisk")
            ||pkg.equals("ru.ivi.client")||pkg.equals("ru.ivi")
            ||pkg.equals("com.ivi.ru")||pkg.contains("ivi.client");
    }
    static String sourceName(String pkg){
        if(pkg==null)return "Видео";
        if(pkg.contains("youtube"))return "YouTube";
        if(pkg.contains("kinopoisk"))return "Кинопоиск";
        if(pkg.contains("ivi"))return "Иви";
        return "Видео";
    }

    // Only media-session notifications (not recommendations, chats or marketing).
    static synchronized void onMediaNotification(Context ctx,String pkg,Notification n){
        if(!TrackStore.active(ctx)||!isVideo(pkg)||n==null||n.extras==null)return;
        Bundle extras=n.extras;
        boolean media=extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            ||Notification.CATEGORY_TRANSPORT.equals(n.category);
        if(!media)return;
        CharSequence title=extras.getCharSequence(Notification.EXTRA_TITLE);
        if(title==null)return;
        String value=title.toString().trim();
        if(isGeneric(value)||value.equals(sourceName(pkg)))return;
        p(ctx).edit().putString("hint_package",pkg).putString("hint_title",value).apply();
    }

    private static boolean isGeneric(String title){
        return title==null||title.trim().isEmpty()
            ||title.equalsIgnoreCase("Название не передано плеером")
            ||title.equalsIgnoreCase("Unknown")
            ||title.equalsIgnoreCase("YouTube")
            ||title.equalsIgnoreCase("Кинопоиск")
            ||title.equalsIgnoreCase("Иви");
    }

    static synchronized void observed(Context ctx,String pkg,String title,boolean playing){
        if(!TrackStore.active(ctx)||!isVideo(pkg))return;
        SharedPreferences s=p(ctx);
        String name=sourceName(pkg);
        title=title==null?"":title.trim();
        if(isGeneric(title)){
            if(pkg.equals(s.getString("hint_package","")))
                title=s.getString("hint_title","");
            if(isGeneric(title)&&pkg.equals(s.getString("package","")))
                title=s.getString("title","");
        }
        if(isGeneric(title)){
            // No meaningful public media title; do not add a fake title to history.
            s.edit().putString("package",pkg).putString("title","").putString("app",name)
               .putBoolean("playing",playing)
               .putString("status","Название не опубликовано медиаплеером").apply();
            return;
        }
        if(pkg.equals(s.getString("package",""))
          &&title.equals(s.getString("title",""))
          &&playing==s.getBoolean("playing",false))return;
        s.edit().putString("package",pkg).putString("title",title)
           .putBoolean("playing",playing).putString("app",name)
           .putString("status","").apply();
        try{
            JSONObject e=new JSONObject();
            e.put("at",System.currentTimeMillis());
            e.put("app",name);e.put("title",title);e.put("playing",playing);
            JSONArray before=new JSONArray(s.getString("log","[]"));
            JSONArray out=new JSONArray();out.put(e);
            for(int i=0;i<before.length()&&i<199;i++)out.put(before.get(i));
            s.edit().putString("log",out.toString()).apply();
        }catch(Exception ignored){}
    }
    static synchronized String current(Context ctx){
        SharedPreferences s=p(ctx);
        String name=s.getString("app","");
        if(name.isEmpty())return "Видео пока не обнаружено";
        String title=s.getString("title","");
        if(title.isEmpty())title=s.getString("status","Название недоступно");
        return name+": "+title+
            (s.getBoolean("playing",false)?" · играет":" · пауза");
    }
    static synchronized String report(Context ctx){
        SharedPreferences s=p(ctx);
        JSONArray list;
        try{list=new JSONArray(s.getString("log","[]"));}catch(Exception e){return "";}
        if(list.length()==0)return "\nВидеособытий нет.\n";
        StringBuilder text=new StringBuilder("\nВидео, метаданные Android (не видеоряд):\n");
        DateFormat f=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
        for(int i=list.length()-1;i>=0;i--){
            JSONObject e=list.optJSONObject(i);if(e==null)continue;
            text.append(f.format(new Date(e.optLong("at")))).append(" ")
                .append(e.optBoolean("playing")?"▶ ":"⏸ ")
                .append(e.optString("app")).append(": ").append(e.optString("title")).append("\n");
        }
        return text.toString();
    }
    private VideoStore(){}
}
