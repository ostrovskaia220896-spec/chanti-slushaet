package ru.chanti.slushaet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Rotates valid AAC/M4A files every 5 minutes.
 * Completed chunks survive app termination; current (unfinished) chunk may not.
 * A visible foreground microphone service supports screen-locked recording.
 */
public final class MagirRecorderService extends Service {
    public static final String START="ru.chanti.slushaet.RECORD_START";
    public static final String STOP="ru.chanti.slushaet.RECORD_STOP";
    public static final String PREF="magir_local_recording";
    private static final long CHUNK_MS=5*60*1000L;
    private static final int NOTICE_ID=102;
    private MediaRecorder recorder;
    private File output, pendingOutput, sessionDir;
    private int chunkIndex;
    private boolean recording;
    private static volatile boolean activeRecording;
    public static boolean isRecording(){return activeRecording;}
    static void reconcile(Context context){
        android.content.SharedPreferences p=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        if(p.getBoolean("recording",false)&&!activeRecording){
            String path=p.getString("last_session","");
            if(!path.isEmpty()){
                File candidate=new File(path);
                File session=MagirSessions.find(context,candidate.getName());
                if(session!=null)MagirSessions.markStopped(session,System.currentTimeMillis());
            }
            p.edit().putBoolean("recording",false)
                .putLong("stopped_at",System.currentTimeMillis())
                .putString("last_error",
                    "Запись была прервана системой. Завершённые фрагменты сохранены; последний мог остаться незавершённым.")
                .apply();
        }
    }
    private PowerManager.WakeLock wake;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable rotate=new Runnable(){
        @Override public void run() {
            if(!recording)return;
            if(!finishSegment()) {fail("Ошибка сохранения фрагмента");return;}
            if(!newSegment()) {fail("Не удалось продолжить запись");return;}
            handler.postDelayed(this,CHUNK_MS);
        }
    };

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null&&Build.VERSION.SDK_INT>=26)
            n.createNotificationChannel(new NotificationChannel("magir_record",
                "Запись Магира",NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notice(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent activity=PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop=new Intent(this,MagirRecorderService.class).setAction(STOP);
        PendingIntent stopAction=PendingIntent.getService(this,1,stop,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"magir_record")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Идёт запись «Миров Магира»")
            .setContentText("Фрагмент "+Math.max(1,chunkIndex)+". Запись продолжится при блокировке экрана.")
            .setContentIntent(activity)
            .addAction(android.R.drawable.ic_media_pause,"Завершить запись",stopAction)
            .setOngoing(true).build();
    }

    private android.content.SharedPreferences prefs(){
        return getSharedPreferences(PREF,MODE_PRIVATE);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){
            finishSession();stopSelf();return START_NOT_STICKY;
        }
        if(intent==null||!START.equals(intent.getAction()))return START_NOT_STICKY;
        if(recording)return START_NOT_STICKY;
        try{
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTICE_ID,notice(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTICE_ID,notice());
            File root=getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if(root==null)throw new IllegalStateException("Хранилище недоступно");
            sessionDir=MagirSessions.createRecordingSession(this);
            MagirSessions.setSelected(this,sessionDir);
            chunkIndex=0;
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(pm!=null){
                wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Chanti:MagirRecording");
                wake.setReferenceCounted(false);
                wake.acquire(6L*60*60*1000);
            }
            prefs().edit().putBoolean("recording",true)
                .putLong("started_at",System.currentTimeMillis())
                .putString("last_session",sessionDir.getAbsolutePath())
                .putString("last_error","").apply();
            recording=true;
            activeRecording=true;
            if(!newSegment())throw new IllegalStateException("Нет звука");
            handler.postDelayed(rotate,CHUNK_MS);
        }catch(Exception e){
            fail("Не удалось начать запись: "+e.getClass().getSimpleName());
        }
        return START_NOT_STICKY;
    }

    private boolean newSegment(){
        chunkIndex++;
        String fileName=String.format(Locale.US,"part_%04d.m4a",chunkIndex);
        output=new File(sessionDir,fileName);
        pendingOutput=new File(sessionDir,fileName+".pending");
        try{
            if(pendingOutput.exists()&&!pendingOutput.delete())
                throw new IllegalStateException("Не освободить временный файл");
            recorder=new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(pendingOutput.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            prefs().edit().putInt("chunks_completed",chunkIndex-1).apply();
            NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(n!=null)n.notify(NOTICE_ID,notice());
            return true;
        }catch(Exception e){
            releaseRecorder();
            if(pendingOutput!=null&&pendingOutput.length()==0)pendingOutput.delete();
            output=null;pendingOutput=null;
            return false;
        }
    }

    private void releaseRecorder(){
        if(recorder==null)return;
        try{recorder.reset();}catch(RuntimeException ignored){}
        try{recorder.release();}catch(RuntimeException ignored){}
        recorder=null;
    }

    private boolean finishSegment(){
        if(recorder==null)return false;
        boolean stopped=false;
        try{recorder.stop();stopped=true;}catch(RuntimeException ignored){}
        releaseRecorder();
        boolean saved=false;
        if(stopped&&pendingOutput!=null&&pendingOutput.isFile()&&pendingOutput.length()>0
            &&output!=null&&!output.exists()){
            saved=pendingOutput.renameTo(output);
        }
        if(saved){
            prefs().edit().putInt("chunks_completed",chunkIndex).apply();
        }else{
            if(pendingOutput!=null&&pendingOutput.isFile()&&pendingOutput.length()==0)
                pendingOutput.delete();
            prefs().edit().putString("last_error",
                "Последний фрагмент не удалось завершить. Незавершённый файл сохранён отдельно.").apply();
        }
        output=null;pendingOutput=null;
        return saved;
    }

    private void fail(String reason){
        prefs().edit().putString("last_error",reason).apply();
        finishSession();
        stopSelf();
    }

    private void finishSession(){
        handler.removeCallbacks(rotate);
        if(!recording){
            activeRecording=false;
            releaseRecorder();
            if(wake!=null&&wake.isHeld())wake.release();
            wake=null;
            prefs().edit().putBoolean("recording",false)
                .putLong("stopped_at",System.currentTimeMillis()).apply();
            stopForeground(true);
            return;
        }
        boolean finalPartSaved=finishSegment();
        recording=false;
        activeRecording=false;
        long stoppedAt=System.currentTimeMillis();
        MagirSessions.markStopped(sessionDir,stoppedAt);
        if(!finalPartSaved&&chunkIndex>0){
            prefs().edit().putString("last_error",
                "Запись остановлена. Последний фрагмент сохранён как незавершённый; предыдущие части целы.").apply();
        }
        prefs().edit().putBoolean("recording",false)
            .putLong("stopped_at",stoppedAt).apply();
        if(wake!=null&&wake.isHeld())wake.release();
        wake=null;
        stopForeground(true);
    }

    @Override public void onDestroy(){
        finishSession();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
