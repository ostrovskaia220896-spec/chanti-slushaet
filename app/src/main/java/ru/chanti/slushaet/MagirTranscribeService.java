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
import org.vosk.Model;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Offline transcription after explicit model-download consent.
 * Completed 5-minute parts are checkpointed; never uploads game recordings.
 */
public final class MagirTranscribeService extends Service {
    public static final String START="ru.chanti.slushaet.TRANSCRIBE";
    public static final String STOP="ru.chanti.slushaet.TRANSCRIBE_STOP";
    public static final String PREF="magir_transcribe_v05";
    private volatile boolean running;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private int notificationId=107;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null&&Build.VERSION.SDK_INT>=26)
            manager.createNotificationChannel(new NotificationChannel("magir_asr",
                "Расшифровка Магира",NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String title){
        PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,MagirTranscribeService.class).setAction(STOP);
        PendingIntent end=PendingIntent.getService(this,2,stop,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"magir_asr")
            .setContentTitle("Расшифровываем «Миры Магира»")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true).setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause,"Остановить",end)
            .build();
    }

    private android.content.SharedPreferences p(){return getSharedPreferences(PREF,MODE_PRIVATE);}
    private void status(String text){
        p().edit().putString("status",text).apply();
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null)n.notify(notificationId,notification(text));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP.equals(intent.getAction())){
            if(worker!=null)worker.interrupt();
            return START_NOT_STICKY;
        }
        if(intent==null||!START.equals(intent.getAction())||running)return START_NOT_STICKY;
        String session=getSharedPreferences(MagirRecorderService.PREF,MODE_PRIVATE)
                .getString("last_session","");
        if(session.isEmpty()||!new File(session).isDirectory()){
            p().edit().putString("status","Нет сохранённой записи").apply();
            stopSelf();return START_NOT_STICKY;
        }
        try{
            if(Build.VERSION.SDK_INT>=29)startForeground(notificationId,notification("Подготовка…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(notificationId,notification("Подготовка…"));
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(pm!=null){
                wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Chanti:Transcribe");
                wake.setReferenceCounted(false);
                wake.acquire(6L*60*60*1000);
            }
            running=true;
            p().edit().putBoolean("running",true).putString("status","Подготовка").apply();
            final File dir=new File(session);
            worker=new Thread(()->runTranscription(dir),"Magir-ASR");
            worker.start();
        }catch(Exception e){
            p().edit().putBoolean("running",false).putString("status",
                "Не удалось начать: "+e.getClass().getSimpleName()).apply();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void runTranscription(File dir){
        try{
            File modelDir=VoskModelInstaller.ensure(this,this::status);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            File[] parts=dir.listFiles((parent,name)->name.matches("part_\\d{4}\\.m4a"));
            if(parts==null||parts.length==0)throw new IllegalStateException("Нет фрагментов записи");
            Arrays.sort(parts,Comparator.comparing(File::getName));
            File transcript=new File(dir,"transcript_ru.txt");
            String progressKey="last_index_"+dir.getName();
            int nextPart=p().getInt(progressKey,0);
            double base=0;
            for(int i=0;i<Math.min(parts.length,nextPart);i++)base+=MagirSpeechDecoder.duration(parts[i]);
            try(Model model=new Model(modelDir.getAbsolutePath())){
                for(int i=nextPart;i<parts.length;i++){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    final int num=i+1;
                    status("Расшифровка фрагмента "+num+" из "+parts.length);
                    if(i==0&&transcript.length()==0){
                        try(FileWriter out=new FileWriter(transcript,true)){
                            out.write("МИРЫ МАГИРА — автоматическая черновая расшифровка (Vosk ru small)\n"+
                                "Текст может содержать ошибки в именах, репликах и распределении голосов.\n"+
                                "Отметки времени приблизительны. Исходные записи сохранены отдельно.\n\n");
                        }
                    }
                    MagirSpeechDecoder.transcribe(parts[i],model,transcript,base,this::status);
                    base+=MagirSpeechDecoder.duration(parts[i]);
                    p().edit().putInt(progressKey,i+1).apply();
                }
            }
            status("Готово! Текст сохранён: transcript_ru.txt");
        }catch(InterruptedException e){
            status("Расшифровка остановлена. Завершённые части сохранены.");
        }catch(Exception e){
            status("Ошибка распознавания: "+e.getClass().getSimpleName()+": "+e.getMessage());
        }finally{
            running=false;
            p().edit().putBoolean("running",false).apply();
            stopSelf();
        }
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
