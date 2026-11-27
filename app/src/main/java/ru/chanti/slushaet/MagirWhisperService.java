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
import org.json.JSONArray;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * One-at-a-time offline Whisper worker with a durable queue and per-fragment checkpoints.
 * Recording another session never changes the session currently being transcribed.
 */
public final class MagirWhisperService extends Service {
    static final String START="ru.chanti.slushaet.WHISPER_START";
    static final String STOP="ru.chanti.slushaet.WHISPER_STOP";
    static final String PREF="magir_whisper_status_v10";
    static final String EXTRA_SESSION="session_name";
    private static final int ID=108;
    private static volatile boolean processRunning=false;
    private volatile boolean running=false;
    private String runningSession="";
    private Thread worker;
    private PowerManager.WakeLock wake;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile String phase="";
    private volatile long phaseStarted=0;
    private final Runnable progressTick=new Runnable(){
        @Override public void run(){
            if(!running||phase.isEmpty())return;
            status(phase+" · прошло "+elapsed(System.currentTimeMillis()-phaseStarted));
            main.postDelayed(this,10000);
        }
    };

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null&&Build.VERSION.SDK_INT>=26)
            n.createNotificationChannel(new NotificationChannel("magir_whisper",
                "Точная расшифровка Магира",NotificationManager.IMPORTANCE_LOW));
    }
    private SharedPreferences p(){return getSharedPreferences(PREF,MODE_PRIVATE);}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,MODE_PRIVATE);}
    private static ArrayList<String> readQueue(Context c){
        ArrayList<String> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(prefs(c).getString("queue","[]"));
            for(int i=0;i<a.length();i++){
                String name=a.optString(i,"");
                if(MagirQueueRules.validSessionName(name)&&!out.contains(name))out.add(name);
            }
        }catch(Exception ignored){}
        return out;
    }
    private void writeQueue(ArrayList<String> queue){
        JSONArray a=new JSONArray();
        for(String name:queue)a.put(name);
        p().edit().putString("queue",a.toString()).apply();
    }
    static boolean isProcessRunning(){return processRunning;}
    static boolean isBusy(Context c){
        reconcile(c);
        return processRunning||prefs(c).getBoolean("running",false);
    }
    static boolean isQueuedOrRunning(Context c,String name){
        if(name==null)return false;
        reconcile(c);
        if(name.equals(prefs(c).getString("active_session",""))&&processRunning)return true;
        return processRunning&&readQueue(c).contains(name);
    }
    /** A persisted running flag with no live service worker means Android killed the process. */
    static void reconcile(Context c){
        SharedPreferences s=prefs(c);
        if(s.getBoolean("running",false)&&!processRunning){
            String stale=s.getString("active_session","");
            SharedPreferences.Editor edit=s.edit().putBoolean("running",false)
                .remove("active_session");
            if(!stale.isEmpty())edit.putString("status_for_"+stale,
                "Обработка прервана системой. Готовые части сохранены — можно продолжить.");
            edit.apply();
        }
    }
    private Notification notice(String message){
        PendingIntent open=PendingIntent.getActivity(this,0,
            new Intent(this,MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,2,
            new Intent(this,MagirWhisperService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"magir_whisper")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Чанти — расшифровка Whisper")
            .setContentText(message).setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause,"Остановить очередь",stop)
            .build();
    }
    private void ensureForeground(String message){
        if(Build.VERSION.SDK_INT>=29)
            startForeground(ID,notice(message),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID,notice(message));
    }
    private void status(String text){
        p().edit().putString("status",text)
            .putString("status_for_"+runningSession,text).apply();
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null)n.notify(ID,notice(text));
    }
    private void setSessionStatus(String session,String text){
        p().edit().putString("status_for_"+session,text).apply();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){
            ArrayList<String> queue=readQueue(this);
            for(String name:queue)setSessionStatus(name,"Очередь отменена пользователем.");
            writeQueue(new ArrayList<>());
            if(worker!=null)worker.interrupt();
            else{stopForeground(true);stopSelf();}
            return START_NOT_STICKY;
        }
        if(intent==null||!START.equals(intent.getAction()))return START_NOT_STICKY;
        reconcile(this);
        String name=intent.getStringExtra(EXTRA_SESSION);
        File session=MagirSessions.find(this,name);
        if(session==null){
            p().edit().putString("status","Нет сохранённой записи").apply();
            if(!running)stopSelf(startId);
            return START_NOT_STICKY;
        }
        if(MagirSessions.audio(session).length==0){
            setSessionStatus(name,"Нет завершённых аудиофрагментов для расшифровки.");
            if(!running)stopSelf(startId);
            return START_NOT_STICKY;
        }
        if(MagirSessions.fullyTranscribed(session)&&MagirSessions.fullyTimed(session)){
            setSessionStatus(name,"Готово — все аудиофрагменты уже расшифрованы.");
            if(!running)stopSelf(startId);
            return START_NOT_STICKY;
        }
        try{
            ensureForeground("Добавляю запись в очередь…");
            ArrayList<String> queue=readQueue(this);
            boolean active=name.equals(runningSession)&&running;
            if(!active&&!MagirQueueRules.contains(queue,name)){
                queue=MagirQueueRules.enqueue(queue,name);
                writeQueue(queue);
                setSessionStatus(name,"В очереди на расшифровку · позиция "+queue.size());
            }
            if(!running)startNext();
        }catch(Exception e){
            setSessionStatus(name,"Ошибка запуска: "+e.getClass().getSimpleName()+": "+e.getMessage());
            if(!running){stopForeground(true);stopSelf();}
        }
        return START_NOT_STICKY;
    }

    private void startNext(){
        if(running)return;
        ArrayList<String> queue=readQueue(this);
        while(!queue.isEmpty()){
            String name=MagirQueueRules.first(queue);
            File session=MagirSessions.find(this,name);
            if(session==null||(MagirSessions.fullyTranscribed(session)&&MagirSessions.fullyTimed(session))||
                MagirSessions.audio(session).length==0){
                queue=MagirQueueRules.withoutFirst(queue);
                writeQueue(queue);
                setSessionStatus(name,session==null?"Запись не найдена — пропускаю очередь.":
                    MagirSessions.fullyTranscribed(session)?
                    "Готово — все аудиофрагменты уже расшифрованы.":
                    "Нет завершённых аудиофрагментов.");
                continue;
            }
            runningSession=name;
            running=true;
            acquireWakeLock();
            processRunning=true;
            p().edit().putBoolean("running",true).putString("active_session",name)
                .putString("status_for_"+name,"Готовлю Whisper…").apply();
            File selected=session;
            try{
                worker=new Thread(()->run(selected),"Whisper-"+name);
                worker.start();
                // Keep the active item in the queue until Thread.start succeeds.
                queue=MagirQueueRules.withoutFirst(queue);
                writeQueue(queue);
            }catch(Exception e){
                worker=null;running=false;processRunning=false;
                p().edit().putBoolean("running",false).remove("active_session").apply();
                setSessionStatus(name,"Не удалось запустить обработку. Нажми расшифровку ещё раз.");
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
            }
            return;
        }
        processRunning=false;
        p().edit().putBoolean("running",false).remove("active_session").apply();
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private void beginPart(String message){
        phase=message;
        phaseStarted=System.currentTimeMillis();
        main.removeCallbacks(progressTick);
        main.postDelayed(progressTick,10000);
        status(message+" · начато");
    }
    private void endPart(){
        phase="";
        main.removeCallbacks(progressTick);
    }
    private static String elapsed(long ms){
        long sec=Math.max(0,ms/1000);
        return String.format(java.util.Locale.US,"%02d:%02d",sec/60,sec%60);
    }

    private void run(File dir){
        WhisperEngine engine=null;
        try{
            File model=WhisperModelInstaller.install(this,this::status);
            File[] parts=MagirSessions.audio(dir);
            if(parts.length==0)throw new IllegalStateException("Завершённых аудиофрагментов нет");
            engine=new WhisperEngine(this,model.getAbsolutePath());
            status("Загружаю модель в память телефона…");
            engine.open();
            double offset=0;
            for(int i=0;i<parts.length;i++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                File part=parts[i];
                File complete=new File(dir,MagirFileRules.transcriptForAudio(part.getName()));
                File temp=new File(dir,part.getName().replace(".m4a",".whisper.tmp"));
                File timed=new File(dir,MagirFileRules.segmentsForAudio(part.getName()));
                File timedTemp=new File(dir,timed.getName()+".tmp");
                double length=MagirSpeechDecoder.duration(part);
                if(length<=0)throw new IllegalStateException("Не читается "+part.getName());
                boolean textMissing=!complete.exists()||complete.length()==0;
                boolean timedMissing=!timed.exists()||timed.length()==0;
                if(textMissing||timedMissing){
                    String label="Whisper: часть "+(i+1)+" из "+parts.length;
                    beginPart(label);
                    File wav=null;
                    try{
                        if(temp.exists())temp.delete();
                        wav=MagirWavConverter.convert(part,getCacheDir());
                        engine.transcribe(wav,temp,timedTemp,offset);
                        if(!temp.exists())throw new IllegalStateException("Не создан черновик");
                        if(temp.length()==0){
                            try(FileWriter f=new FileWriter(temp,true)){f.write("\n");}
                        }
                        if(!timedTemp.isFile()||timedTemp.length()==0)
                            throw new IllegalStateException("Не сохранены временные границы реплик");
                        MagirSessions.replaceFile(timedTemp,timed);
                        if(textMissing)MagirSessions.replaceFile(temp,complete);
                        else if(temp.exists())temp.delete();
                        endPart();
                        rebuild(dir,parts,new File(dir,"transcript_whisper_ru.txt"));
                        status("Сохранено: часть "+(i+1)+" из "+parts.length);
                    }finally{
                        endPart();
                        if(wav!=null)wav.delete();
                        if(temp.exists())temp.delete();
                        if(timedTemp.exists())timedTemp.delete();
                    }
                }else{
                    status("Проверяю сохранённые части: "+(i+1)+" из "+parts.length);
                }
                offset+=length;
            }
            rebuild(dir,parts,new File(dir,"transcript_whisper_ru.txt"));
            status("Готово! Точная расшифровка сохранена (TXT)");
        }catch(InterruptedException e){
            status("Остановлено. Готовые части сохранены, можно продолжить.");
        }catch(Exception e){
            status("Ошибка Whisper: "+e.getClass().getSimpleName()+": "+e.getMessage());
        }finally{
            endPart();
            if(engine!=null)try{engine.close();}catch(Exception ignored){}
            main.post(()->finishCurrent(dir));
        }
    }

    private void finishCurrent(File dir){
        running=false;
        worker=null;
        processRunning=false;
        p().edit().putBoolean("running",false).remove("active_session").apply();
        if(!readQueue(this).isEmpty()){
            startNext();
        }else{
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }
    private void acquireWakeLock(){
        if(wake!=null&&wake.isHeld())return;
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null){
            wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Chanti:WhisperASR");
            wake.setReferenceCounted(false);
            wake.acquire(6L*60*60*1000);
        }
    }
    private void releaseWakeLock(){
        if(wake!=null&&wake.isHeld())wake.release();
        wake=null;
    }
    private void rebuild(File dir,File[] parts,File output)throws Exception{
        File tmp=new File(dir,"transcript_whisper_ru.tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            out.write(("МИРЫ МАГИРА — черновая офлайн-расшифровка Whisper Small (ru)\n"+
                "Временные отметки приблизительные; фэнтезийные имена проверяй по записи.\n"+
                "Кто говорит, автоматически не определяется.\n\n").getBytes("UTF-8"));
            for(File part:parts){
                File text=new File(dir,MagirFileRules.transcriptForAudio(part.getName()));
                if(!text.exists())continue;
                try(FileInputStream in=new FileInputStream(text)){
                    byte[] buffer=new byte[32*1024];int n;
                    while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                }
            }
            out.getFD().sync();
        }
        MagirSessions.replaceFile(tmp,output);
    }
    @Override public void onDestroy(){
        main.removeCallbacks(progressTick);
        if(worker!=null&&running)worker.interrupt();
        releaseWakeLock();
        processRunning=false;
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
