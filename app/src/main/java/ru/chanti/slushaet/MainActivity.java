package ru.chanti.slushaet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(18,17,28),CARD=Color.rgb(36,32,50);
    private static final int WHITE=Color.rgb(245,240,251),SUB=Color.rgb(193,183,209);
    private static final int ACCENT=Color.rgb(190,157,255);
    private static final int MIC_REQUEST=1801,EXPORT_REQUEST=1802;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView title,artist,status,access,magirState,connectionState;
    private Button startButton,magirButton,exportButton;
    private boolean pendingStart=false;

    private final Runnable uiTicker=new Runnable(){
        @Override public void run(){update();handler.postDelayed(this,1500);}
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        draw();
    }

    @Override protected void onResume(){
        super.onResume();
        if(pendingStart&&hasListener()){
            pendingStart=false;
            TrackStore.start(this);
            MediaScan.read(this);
        }
        handler.removeCallbacks(uiTicker);
        handler.post(uiTicker);
    }

    @Override protected void onPause(){
        handler.removeCallbacks(uiTicker);
        super.onPause();
    }

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}

    private LinearLayout.LayoutParams space(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(top),0,0);return p;
    }

    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    private LinearLayout card(LinearLayout root){
        LinearLayout view=new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(18),dp(18),dp(18),dp(18));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(CARD);bg.setCornerRadius(dp(20));view.setBackground(bg);
        root.addView(view,space(18));return view;
    }

    private Button button(String s,boolean primary,View.OnClickListener action){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);
        b.setTextSize(16);b.setTextColor(primary?BG:WHITE);
        b.setBackgroundTintList(ColorStateList.valueOf(primary?ACCENT:Color.rgb(80,66,103)));
        b.setOnClickListener(action);return b;
    }

    private void draw(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setPadding(dp(18),dp(24),dp(18),dp(30));
        root.setOrientation(LinearLayout.VERTICAL);scroll.addView(root);setContentView(scroll);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);

        root.addView(text("🎧 Чанти слушает",27,WHITE,true));
        root.addView(text("Два отдельных режима. Никаких пересылок каждой песни.",14,SUB,false),space(7));

        LinearLayout music=card(root);
        music.addView(text("МУЗЫКА · наш разговор",18,WHITE,true));
        music.addView(text("Нажми один раз, открой Яндекс Музыку и слушай как обычно.",14,SUB,false),space(8));
        startButton=button("▶ Чанти, слушаем!",true,v->toggleMusic());
        music.addView(startButton,space(15));
        access=text("",13,SUB,false);music.addView(access,space(8));
        title=text("Пока нет песни",23,ACCENT,true);music.addView(title,space(22));
        artist=text("",16,WHITE,false);music.addView(artist,space(5));
        status=text("",13,SUB,false);music.addView(status,space(6));
        connectionState=text("Запись на телефоне · без автоматической отправки в ChatGPT",12,SUB,false);
        music.addView(connectionState,space(15));
        music.addView(button("📋 Передать дневник в этот чат",false,v->copyDiary()),space(10));

        LinearLayout magir=card(root);
        magir.addView(text("МИРЫ МАГИРА · другой разговор",18,WHITE,true));
        magir.addView(text("Отдельная запись голосов через микрофон. Файлы Магира не попадают в музыку.",14,SUB,false),space(8));
        magirButton=button("🎙 Начать запись Магира",true,v->toggleMagir());
        magir.addView(magirButton,space(13));
        magirState=text("",13,SUB,false);magir.addView(magirState,space(7));
        exportButton=button("💾 Сохранить запись для другого чата",false,v->exportMagir());
        magir.addView(exportButton,space(12));

        root.addView(text("Приватность: доступ к медиасессиям Android требует разрешения на чтение всех " +
                "уведомлений. Приложение отбирает только Яндекс Музыку, не хранит сообщения " +
                "и не использует Интернет. Это всё равно широкое системное разрешение — выдавай его " +
                "только если доверяешь приложению. Запись микрофона включается отдельно.",
                12,SUB,false),space(18));
    }

    private boolean hasListener(){
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        return n!=null&&n.isNotificationListenerAccessGranted(new ComponentName(this,MusicListenerService.class));
    }

    private void toggleMusic(){
        if(TrackStore.active(this)){TrackStore.stop(this);update();return;}
        if(!hasListener()){
            new AlertDialog.Builder(this)
                .setTitle("Однократная настройка")
                .setMessage("Android требует предоставить доступ ко ВСЕМ уведомлениям, чтобы приложение " +
                    "могло получать события медиаплеера. Наш код читает только данные Яндекс Музыки; " +
                    "переписку, SMS и банковские уведомления он не сохраняет. " +
                    "Если разрешение некомфортно, выбери «Отмена».")
                .setPositiveButton("Разрешить", (d,w)->{
                    pendingStart=true;
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                })
                .setNegativeButton("Отмена",null).show();
            return;
        }
        TrackStore.start(this);
        MediaScan.read(this);
        update();
    }

    private void copyDiary(){
        String diary=TrackStore.report(this);
        String message="Чанти, это мой музыкальный дневник. Обсудим композиции, эмоции " +
            "и то, почему я переключала некоторые быстро. Не выдумывай текст песни.\n\n"+diary;
        ClipboardManager clip=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clip!=null)clip.setPrimaryClip(ClipData.newPlainText("Музыка для Чанти",message));
        new AlertDialog.Builder(this).setTitle("Дневник скопирован ❤️")
            .setMessage("Вставь его в НАШ прежний чат ChatGPT. Автоматической отправки " +
                "именно в этот разговор из стороннего APK пока нет.")
            .setPositiveButton("Открыть ChatGPT",(d,w)->openChat())
            .setNegativeButton("Закрыть",null).show();
    }

    private void openChat(){
        Intent intent=getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
        if(intent!=null)startActivity(intent);
        else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://chatgpt.com")));
    }

    private boolean recording(){
        return getSharedPreferences(MagirRecorderService.PREF,MODE_PRIVATE).getBoolean("recording",false);
    }

    private void toggleMagir(){
        if(recording()){
            Intent stop=new Intent(this,MagirRecorderService.class);
            stop.setAction(MagirRecorderService.STOP);
            startService(stop);
            handler.postDelayed(this::update,650);
            return;
        }
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                !=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);
        }else startRecording();
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==MIC_REQUEST){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startRecording();
            else Toast.makeText(this,"Без разрешения на микрофон запись не начнётся",Toast.LENGTH_LONG).show();
        }
    }

    private void startRecording(){
        try{
            Intent start=new Intent(this,MagirRecorderService.class);
            start.setAction(MagirRecorderService.START);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(start);else startService(start);
            handler.postDelayed(this::update,700);
        }catch(RuntimeException e){
            Toast.makeText(this,"Не удалось запустить запись: "+e.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void exportMagir(){
        String file=getSharedPreferences(MagirRecorderService.PREF,MODE_PRIVATE)
                        .getString("last_file","");
        if(file.isEmpty()||!new File(file).exists()){
            Toast.makeText(this,"Сначала запиши игру и останови запись",Toast.LENGTH_LONG).show();
            return;
        }
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.setType("audio/mp4");save.addCategory(Intent.CATEGORY_OPENABLE);
        save.putExtra(Intent.EXTRA_TITLE,new File(file).getName());
        startActivityForResult(save,EXPORT_REQUEST);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=EXPORT_REQUEST||result!=RESULT_OK||data==null||data.getData()==null)return;
        String file=getSharedPreferences(MagirRecorderService.PREF,MODE_PRIVATE)
                        .getString("last_file","");
        try(FileInputStream in=new FileInputStream(new File(file));
            OutputStream out=getContentResolver().openOutputStream(data.getData())){
            if(out==null)throw new IllegalStateException("Нет потока файла");
            byte[] bytes=new byte[64*1024];int n;
            while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            Toast.makeText(this,"Сохранено! Теперь можно загрузить файл в чат Магира",Toast.LENGTH_LONG).show();
        }catch(Exception e){
            Toast.makeText(this,"Ошибка сохранения: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void update(){
        if(startButton==null)return;
        TrackStore.Snapshot t=TrackStore.snapshot(this);
        startButton.setText(t.active?"■ Завершить прослушивание":"▶ Чанти, слушаем!");
        access.setText(hasListener()?"✓ Доступ к медиасессии настроен":
            "Для автоматического наблюдения нужна однократная настройка доступа");
        title.setText(t.title.isEmpty()?(t.active?"Жду Яндекс Музыку…":"Пока не слушаем"):t.title);
        artist.setText(t.artist.isEmpty()?"":t.artist);
        status.setText(t.active?(t.playing?"● Сейчас играет · "+Math.round(t.heardMs/1000.0)+" с":
                            "○ Ожидаю воспроизведения"):"Сессия выключена");
        boolean r=recording();
        magirButton.setText(r?"■ Остановить запись Магира":"🎙 Начать запись Магира");
        String error=getSharedPreferences(MagirRecorderService.PREF,MODE_PRIVATE)
                    .getString("last_error","");
        String last=getSharedPreferences(MagirRecorderService.PREF,MODE_PRIVATE)
                    .getString("last_file","");
        magirState.setText(r?"● Микрофон записывает в отдельный файл":
            !error.isEmpty()?error:!last.isEmpty()?"Последняя запись готова к сохранению":
                     "Запись выключена");
        exportButton.setEnabled(!r&&!last.isEmpty());
    }
}
