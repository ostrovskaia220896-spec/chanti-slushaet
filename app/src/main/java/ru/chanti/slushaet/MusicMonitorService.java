package ru.chanti.slushaet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

/** User-started ongoing diary. Keeps a visible service independently of listener binding.
 * Does not play or record audio, open another app, or acquire a continuous wake lock. */
public final class MusicMonitorService extends Service {
    private static final int ID=110;
    private static final String CHANNEL="music_diary_monitor", STOP="ru.chanti.slushaet.MUSIC_MONITOR_STOP";
    private static volatile boolean running;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long lastRebind=-30000;
    static void ensure(Context context){
        if(running)return;
        try{context.startForegroundService(new Intent(context,MusicMonitorService.class));}
        catch(RuntimeException error){android.util.Log.w("MusicMonitor","Cannot start monitoring",error);}
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,109,new Intent(this,MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,109,new Intent(this,MusicMonitorService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Чанти — музыкальный дневник")
            .setContentText("Отслеживание музыки включено").setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause,"Завершить",stop).setOngoing(true).build();
    }
    private final Runnable tick=new Runnable(){
        @Override public void run(){
            if(!TrackStore.active(MusicMonitorService.this)){stopSelf();return;}
            try{
                if(!MusicListenerService.pollConnected()){
                    long now=SystemClock.elapsedRealtime();
                    if(now-lastRebind>=30000){
                        lastRebind=now;
                        NotificationListenerService.requestRebind(new ComponentName(
                            MusicMonitorService.this,MusicListenerService.class));
                    }
                    MediaScan.read(MusicMonitorService.this);
                }
            }catch(RuntimeException error){
                android.util.Log.w("MusicMonitor","Observation unavailable",error);
                TrackStore.lost(MusicMonitorService.this);
            }
            handler.postDelayed(this,2000);
        }
    };
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null)n.createNotificationChannel(new NotificationChannel(CHANNEL,
            "Музыкальный дневник",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){
            TrackStore.stop(this);stopSelf();return START_NOT_STICKY;
        }
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID,notification());
            if(!TrackStore.active(this)){stopSelf();return START_NOT_STICKY;}
            running=true;
            handler.removeCallbacks(tick);handler.post(tick);
            return START_STICKY;
        }catch(RuntimeException error){
            android.util.Log.w("MusicMonitor","Foreground start failed",error);stopSelf();return START_NOT_STICKY;
        }
    }
    @Override public void onDestroy(){
        running=false;handler.removeCallbacks(tick);stopForeground(true);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
