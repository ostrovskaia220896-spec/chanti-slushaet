package ru.chanti.slushaet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Offline Whisper Small transcription with per-fragment checkpoints.
 * The model is fetched only after explicit user consent in MainActivity.
 * Each finished part is cached; interruptions do not force retranscribing completed parts.
 */
public final class MagirWhisperService extends Service {
    static final String START="ru.chanti.slushaet.WHISPER_START";
    static final String STOP="ru.chanti.slushaet.WHISPER_STOP";
    static final String PREF="magir_whisper_status_v06";
    static final String EXTRA_SESSION="session_name";
    private String runningSession="";
    private static final int ID=108;
    private volatile boolean running=false;
    private Thread worker;
    private PowerManager.WakeLock wake;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null&&Build.VERSION.SDK_INT>=26){
            n.createNotificationChannel(new NotificationChannel("magir_whisper",
                "Точная расшифровка Магира",NotificationManager.IMPORTANCE_LOW));
        }
    }

    private android.content.SharedPreferences p(){
        return getSharedPreferences(PREF,MODE_PRIVATE);
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
            .addAction(android.R.drawable.ic_media_pause,"Остановить",stop)
            .build();
    }
    private void status(String text){
        p().edit().putString("status",text)
            .putString("status_for_"+runningSession,text).apply();
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null)n.notify(ID,notice(text));
    }

    @Override public int onStartCommand(Intent i,int flags,int startId){
        if(i!=null&&STOP.equals(i.getAction())){
            if(worker!=null)worker.interrupt();
            return START_NOT_STICKY;
        }
        if(i==null||!START.equals(i.getAction())||running)return START_NOT_STICKY;
        String name=i.getStringExtra(EXTRA_SESSION);
        File session=MagirSessions.find(this,name);
        if(session==null){
            p().edit().putString("status","Нет сохранённой записи").apply();
            stopSelf();return START_NOT_STICKY;
        }
        try{
            if(Build.VERSION.SDK_INT>=29){
                startForeground(ID,notice("Подготовка…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            }else startForeground(ID,notice("Подготовка…"));
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(pm!=null){
                wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Chanti:WhisperASR");
                wake.setReferenceCounted(false);
                wake.acquire(6L*60*60*1000);
            }
            runningSession=session.getName();
            running=true;
            p().edit().putBoolean("running",true)
                .putString("active_session",runningSession)
                .putString("status","Готовлю Whisper").apply();
            worker=new Thread(()->run(session),"Whisper-session");
            worker.start();
        }catch(Exception e){
            p().edit().putBoolean("running",false)
                .putString("status","Ошибка запуска: "+e.getClass().getSimpleName()).apply();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void run(File dir){
        WhisperEngine engine=null;
        try{
            File model=WhisperModelInstaller.install(this,this::status);
            File[] parts=dir.listFiles((folder,name)->name.matches("part_\\d{4}\\.m4a"));
            if(parts==null||parts.length==0)
                throw new IllegalStateException("Записанных фрагментов нет");
            Arrays.sort(parts,Comparator.comparing(File::getName));
            File transcript=new File(dir,"transcript_whisper_ru.txt");
            engine=new WhisperEngine(this,model.getAbsolutePath());
            status("Загружаю модель в память телефона…");
            engine.open();
            double offset=0;
            for(int i=0;i<parts.length;i++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                File part=parts[i];
                File complete=new File(dir,part.getName().replace(".m4a",".whisper.txt"));
                File temp=new File(dir,part.getName().replace(".m4a",".whisper.tmp"));
                double length=MagirSpeechDecoder.duration(part);
                if(length<=0)throw new IllegalStateException("Не читается "+part.getName());
                if(!complete.exists()||complete.length()==0){
                    status("Whisper: часть "+(i+1)+" из "+parts.length+
                        " · обработка на телефоне");
                    File wav=null;
                    try{
                        if(temp.exists())temp.delete();
                        wav=MagirWavConverter.convert(part,getCacheDir());
                        engine.transcribe(wav,temp,offset);
                        if(!temp.exists())throw new IllegalStateException("Не создан черновик");
                        // Even empty speech is valid for silence: zero-sized completed part is marked
                        // with a single newline to avoid repeated processing.
                        if(temp.length()==0){
                            try(FileWriter f=new FileWriter(temp,true)){f.write("\n");}
                        }
                        if(complete.exists())complete.delete();
                        if(!temp.renameTo(complete))
                            throw new IllegalStateException("Нельзя сохранить часть "+(i+1));
                        rebuild(dir,parts,transcript);
                    }finally{
                        if(wav!=null)wav.delete();
                        if(temp.exists())temp.delete();
                    }
                }
                offset+=length;
            }
            rebuild(dir,parts,transcript);
            status("Готово! Точная расшифровка сохранена (TXT)");
        }catch(InterruptedException e){
            status("Остановлено. Готовые части сохранены, можно продолжить.");
        }catch(Exception e){
            status("Ошибка Whisper: "+e.getClass().getSimpleName()+": "+e.getMessage());
        }finally{
            if(engine!=null)engine.close();
            running=false;
            p().edit().putBoolean("running",false).remove("active_session").apply();
            stopSelf();
        }
    }

    private void rebuild(File dir,File[] parts,File output)throws Exception{
        File tmp=new File(dir,"transcript_whisper_ru.tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            out.write(("МИРЫ МАГИРА — черновая офлайн-расшифровка Whisper Small (ru)\n"+
                "Временные отметки приблизительные; фэнтезийные имена проверяй по записи.\n"+
                "Кто говорит, автоматически не определяется.\n\n").getBytes("UTF-8"));
            for(File part:parts){
                File text=new File(dir,part.getName().replace(".m4a",".whisper.txt"));
                if(!text.exists())continue;
                try(FileInputStream in=new FileInputStream(text)){
                    byte[] buffer=new byte[32*1024];int n;
                    while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                }
            }
            out.getFD().sync();
        }
        if(output.exists())output.delete();
        if(!tmp.renameTo(output))throw new IllegalStateException("Не сохранить сводный текст");
    }

    @Override public void onDestroy(){
        if(worker!=null&&running)worker.interrupt();
        if(wake!=null&&wake.isHeld())wake.release();
        wake=null;
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
