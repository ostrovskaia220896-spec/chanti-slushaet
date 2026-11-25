package ru.chanti.slushaet;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** Metadata-only events from supported video players. Never accesses video frames or audio. */
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
    static synchronized void observed(Context ctx,String pkg,String title,boolean playing){
        if(!TrackStore.active(ctx)||pkg==null)return;
        SharedPreferences s=p(ctx);
        String name=sourceName(pkg);
        title=title==null?"":title.trim();
        if(title.isEmpty())title="Название не передано плеером";
        if(pkg.equals(s.getString("package",""))
          &&title.equals(s.getString("title",""))
          &&playing==s.getBoolean("playing",false))return;
        s.edit().putString("package",pkg).putString("title",title)
           .putBoolean("playing",playing).putString("app",name).apply();
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
        return name+": "+s.getString("title","")+
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
