package ru.chanti.slushaet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.File;
import java.util.List;

/** Separate, offline speaker diarization pass. It never modifies Whisper's original transcript. */
public final class MagirDiarizationService extends Service {
    static final String START="ru.chanti.slushaet.DIARIZATION_START";
    static final String STOP="ru.chanti.slushaet.DIARIZATION_STOP";
    static final String PREF="magir_diarization_status_v10";
    static final String EXTRA_SESSION="session_name";
    private static final int ID=109;
    private static volatile boolean processRunning=false;
    private volatile boolean running=false;
    private String runningSession="";
    private Thread worker;
    private PowerManager.WakeLock wake;
    private final Handler main=new Handler(Looper.getMainLooper());

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null&&Build.VERSION.SDK_INT>=26)
            n.createNotificationChannel(new NotificationChannel("magir_diarization",
                "Разделение голосов Магира",NotificationManager.IMPORTANCE_LOW));
    }
    private SharedPreferences p(){return getSharedPreferences(PREF,MODE_PRIVATE);}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,MODE_PRIVATE);}
    static boolean isBusy(Context c){
        reconcile(c);
        return processRunning||prefs(c).getBoolean("running",false);
    }
    static void reconcile(Context c){
        SharedPreferences s=prefs(c);
        if(s.getBoolean("running",false)&&!processRunning){
            String stale=s.getString("active_session","");
            SharedPreferences.Editor edit=s.edit().putBoolean("running",false).remove("active_session");
            if(!stale.isEmpty())edit.putString("status_for_"+stale,
                "Разделение голосов прервано системой. Исходная расшифровка сохранена; можно запустить ещё раз.");
            edit.apply();
        }
    }
    private Notification notice(String message){
        PendingIntent open=PendingIntent.getActivity(this,0,
            new Intent(this,MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,3,
            new Intent(this,MagirDiarizationService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"magir_diarization")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Чанти — разделение голосов")
            .setContentText(message).setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause,"Остановить",stop).build();
    }
    private void foreground(String message){
        if(Build.VERSION.SDK_INT>=29)
            startForeground(ID,notice(message),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID,notice(message));
    }
    private void status(String message){
        p().edit().putString("status",message)
            .putString("status_for_"+runningSession,message).apply();
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null)n.notify(ID,notice(message));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){
            if(worker!=null)worker.interrupt();
            else{stopForeground(true);stopSelf();}
            return START_NOT_STICKY;
        }
        if(intent==null||!START.equals(intent.getAction()))return START_NOT_STICKY;
        reconcile(this);
        String name=intent.getStringExtra(EXTRA_SESSION);
        File session=MagirSessions.find(this,name);
        if(session==null){
            p().edit().putString("status","Запись не найдена").apply();
            stopSelf(startId);return START_NOT_STICKY;
        }
        if(running){
            p().edit().putString("status_for_"+name,
                "Уже обрабатывается другая запись. Дождись её завершения.").apply();
            return START_NOT_STICKY;
        }
        if(!MagirSessions.fullyTranscribed(session)){
            p().edit().putString("status_for_"+name,
                "Сначала полностью расшифруй все фрагменты Whisper.").apply();
            stopSelf(startId);return START_NOT_STICKY;
        }
        if(!MagirSessions.fullyTimed(session)){
            p().edit().putString("status_for_"+name,
                "Сначала обнови временные отметки Whisper для этой записи.").apply();
            stopSelf(startId);return START_NOT_STICKY;
        }
        try{
            foreground("Готовлю модели разделения голосов…");
            running=true;processRunning=true;runningSession=name;
            p().edit().putBoolean("running",true).putString("active_session",name)
                .putString("status_for_"+name,"Готовлю модели разделения голосов…").apply();
            acquireWakeLock();
            worker=new Thread(()->run(session),"Magir-Diarization-"+name);
            worker.start();
        }catch(Exception error){
            running=false;processRunning=false;
            p().edit().putBoolean("running",false).remove("active_session").apply();
            p().edit().putString("status_for_"+name,"Не удалось запустить: "+error.getMessage()).apply();
            releaseWakeLock();stopForeground(true);stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void run(File dir){
        File[] parts=MagirSessions.audio(dir);
        File[] temporary=new File[parts.length];
        File[] finalFiles=new File[parts.length];
        try{
            long lastProgress=0;
            SpeakerModelInstaller.Models models=SpeakerModelInstaller.INSTANCE.install(
                this,(model,downloaded,total)->{
                    long now=System.currentTimeMillis();
                    if(now-lastProgress>1200||downloaded==total){
                        lastProgress=now;
                        status("Скачиваю модели голосов: "+(downloaded/1024/1024)+
                            " из "+(total/1024/1024)+" МиБ");
                    }
                });
            try(MagirSpeakerDiarizationPipeline pipeline=
                    new MagirSpeakerDiarizationPipeline(models.getSegmentation(),
                        models.getEmbedding(),2,0.5f,0.5f,0.06f)){
                double offset=0;
                for(int i=0;i<parts.length;i++){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    File part=parts[i];
                    double length=MagirSpeechDecoder.duration(part);
                    if(length<=0)throw new IllegalStateException("Не читается "+part.getName());
                    File wav=MagirWavConverter.convert(part,getCacheDir());
                    try{
                        status("Различаю голоса: часть "+(i+1)+" из "+parts.length+"…");
                        List<DetectedSpeakerTurn> turns=pipeline.processPart(wav);
                        long offsetMs=Math.round(offset*1000.0);
                        String name=MagirFileRules.speakerTurnsForAudio(part.getName());
                        temporary[i]=new File(dir,name+".tmp");
                        finalFiles[i]=new File(dir,name);
                        MagirDiarizationFiles.writeSidecar(temporary[i],offsetMs,turns);
                    }finally{if(wav.exists())wav.delete();}
                    offset+=length;
                    status("Голоса обработаны: "+(i+1)+" из "+parts.length+
                        " · найдено "+pipeline.getSpeakerCount()+" голосов");
                }
            }
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            File transcriptTemp=new File(dir,"transcript_diarized_ru.build");
            MagirDiarizationFiles.buildTranscript(dir,parts,temporary,transcriptTemp);
            for(int i=0;i<temporary.length;i++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                MagirSessions.replaceFile(temporary[i],finalFiles[i]);
            }
            MagirSessions.replaceFile(transcriptTemp,
                new File(dir,"transcript_diarized_ru.txt"));
            status("Готово! Создана отдельная расшифровка с голосами. Найдено "+
                readSpeakerCount(temporary.length,finalFiles)+" голосов.");
        }catch(InterruptedException stopped){
            status("Разделение остановлено. Исходная расшифровка Whisper сохранена; можно повторить.");
        }catch(Exception error){
            status("Ошибка разделения голосов: "+error.getClass().getSimpleName()+": "+error.getMessage());
        }finally{
            for(File file:temporary)if(file!=null&&file.exists())file.delete();
            main.post(()->finish());
        }
    }
    private int readSpeakerCount(int count,File[] files){
        java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(File file:files){
            if(file==null||!file.isFile())continue;
            try{
                org.json.JSONArray turns=new org.json.JSONObject(
                    new String(java.nio.file.Files.readAllBytes(file.toPath()),"UTF-8"))
                    .optJSONArray("turns");
                if(turns==null)continue;
                for(int i=0;i<turns.length();i++){
                    String id=turns.optJSONObject(i).optString("speakerId","unknown");
                    if(id.startsWith("speaker_"))ids.add(id);
                }
            }catch(Exception ignored){}
        }
        return ids.size();
    }
    private void finish(){
        running=false;processRunning=false;worker=null;
        p().edit().putBoolean("running",false).remove("active_session").apply();
        releaseWakeLock();stopForeground(true);stopSelf();
    }
    private void acquireWakeLock(){
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null){
            wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Chanti:SpeakerDiarization");
            wake.setReferenceCounted(false);wake.acquire(6L*60*60*1000);
        }
    }
    private void releaseWakeLock(){
        if(wake!=null&&wake.isHeld())wake.release();
        wake=null;
    }
    @Override public void onDestroy(){
        if(worker!=null&&running)worker.interrupt();
        releaseWakeLock();processRunning=false;
        stopForeground(true);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
