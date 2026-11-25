package ru.chanti.slushaet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Explicit microphone recording, independent from Yandex Music and its session log. */
public final class MagirRecorderService extends Service {
    public static final String START="ru.chanti.slushaet.RECORD_START";
    public static final String STOP="ru.chanti.slushaet.RECORD_STOP";
    public static final String PREF="magir_local_recording";
    private MediaRecorder recorder;
    private File output;
    private boolean recording=false;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(nm!=null&&Build.VERSION.SDK_INT>=26)
            nm.createNotificationChannel(new NotificationChannel("magir_record","Запись Магира",
                NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP.equals(intent.getAction())){finish();stopSelf();return START_NOT_STICKY;}
        if(intent==null||!START.equals(intent.getAction()))return START_NOT_STICKY;
        if(recording)return START_NOT_STICKY;
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notice=new Notification.Builder(this,"magir_record")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Чанти записывает Магир")
            .setContentText("Запись микрофона включена. Для остановки открой приложение.")
            .setContentIntent(pending).setOngoing(true).build();
        try{
            if(Build.VERSION.SDK_INT>=29)
                startForeground(102,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(102,notice);
            File base=getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if(base==null)throw new IllegalStateException("Нет доступного хранилища");
            File dir=new File(base,"Magir");
            if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Не удалось создать папку");
            String name="Magir_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US)
                .format(new Date())+".m4a";
            output=new File(dir,name);
            recorder=new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(output.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording=true;
            getSharedPreferences(PREF,MODE_PRIVATE).edit()
                .putBoolean("recording",true).putString("last_error","").apply();
        }catch(Exception e){
            getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("recording",false)
                .putString("last_error","Ошибка записи: "+e.getClass().getSimpleName()).apply();
            cleanup(false);
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void cleanup(boolean save){
        if(recorder!=null){
            try{recorder.reset();}catch(RuntimeException ignored){}
            try{recorder.release();}catch(RuntimeException ignored){}
            recorder=null;
        }
        if(!save&&output!=null)output.delete();
        output=null;
    }

    private void finish(){
        boolean success=false;
        if(recording&&recorder!=null){
            try{recorder.stop();success=true;}
            catch(RuntimeException ignored){}
        }
        String path=success&&output!=null?output.getAbsolutePath():"";
        cleanup(success);
        recording=false;
        android.content.SharedPreferences.Editor e=getSharedPreferences(PREF,MODE_PRIVATE).edit()
            .putBoolean("recording",false);
        if(!path.isEmpty())e.putString("last_file",path).putString("last_error","");
        else e.putString("last_error","Запись не сохранилась. Попробуй записать дольше.");
        e.apply();
        stopForeground(true);
    }

    @Override public void onDestroy(){
        if(recording)finish();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i){return null;}
}
