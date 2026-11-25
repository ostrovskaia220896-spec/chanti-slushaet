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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Two independent entrypoints: music diary (current chat) and Magir audio (another chat). */
public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(18,17,28),CARD=Color.rgb(36,32,50);
    private static final int WHITE=Color.rgb(245,240,251),SUB=Color.rgb(193,183,209);
    private static final int ACCENT=Color.rgb(190,157,255);
    private static final int MIC_REQUEST=1801,ZIP_REQUEST=1802,TEXT_REQUEST=1803,MODEL_IMPORT_REQUEST=1804,SESSION_IMPORT_REQUEST=1805;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView musicTitle,musicArtist,musicStatus,listenerStatus,videoStatus;
    private TextView magirState,asrState,selectedSessionInfo;
    private Button listen,record,transcribe,zip,txt;
    private boolean pendingListen=false;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){update();handler.postDelayed(this,1300);}
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        draw();
    }
    @Override protected void onResume(){
        super.onResume();
        if(pendingListen&&hasListener()){
            pendingListen=false;
            TrackStore.start(this);MediaScan.read(this);
        }
        handler.removeCallbacks(refresh);handler.post(refresh);
    }
    @Override protected void onPause(){
        handler.removeCallbacks(refresh);super.onPause();
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private LinearLayout.LayoutParams space(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(top),0,0);return p;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private LinearLayout card(LinearLayout root){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(18),dp(18),dp(18));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(CARD);bg.setCornerRadius(dp(19));box.setBackground(bg);
        root.addView(box,space(16));return box;
    }
    private Button button(String label,boolean primary,android.view.View.OnClickListener listener){
        Button btn=new Button(this);btn.setText(label);btn.setAllCaps(false);btn.setTextSize(15);
        btn.setTextColor(primary?BG:WHITE);
        btn.setBackgroundTintList(ColorStateList.valueOf(primary?ACCENT:Color.rgb(78,68,101)));
        btn.setOnClickListener(listener);return btn;
    }
    private void draw(){
        ScrollView sc=new ScrollView(this);sc.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(24),dp(18),dp(35));
        sc.addView(root);setContentView(sc);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        root.addView(text("🎧 Чанти слушает 0.8",26,WHITE,true));
        root.addView(text("Музыка — для этого чата. «Магир» — для другого.",15,SUB,false),space(8));

        LinearLayout music=card(root);
        music.addView(text("🎶 МУЗЫКА",19,WHITE,true));
        music.addView(text("Нажми одну кнопку и возвращайся в Яндекс Музыку.",14,SUB,false),space(8));
        listen=button("▶ Чанти, слушаем!",true,v->toggleMusic());
        music.addView(listen,space(12));
        listenerStatus=text("",12,SUB,false);music.addView(listenerStatus,space(8));
        musicTitle=text("",22,ACCENT,true);music.addView(musicTitle,space(18));
        musicArtist=text("",16,WHITE,false);music.addView(musicArtist,space(4));
        musicStatus=text("",13,SUB,false);music.addView(musicStatus,space(7));
        music.addView(button("📋 Скопировать журнал для нашего чата",false,v->copyDiary()),space(12));
        music.addView(text("Без автоматической публикации в ChatGPT: приложение не может само " +
            "писать в конкретный существующий мобильный чат.",12,SUB,false),space(9));

        LinearLayout video=card(root);
        video.addView(text("📺 ВИДЕО · проверка медиаданных",18,WHITE,true));
        video.addView(text("YouTube, Кинопоиск, Иви — если плеер публикует название. " +
            "Само видео приложение не видит.",13,SUB,false),space(7));
        videoStatus=text("",14,ACCENT,false);video.addView(videoStatus,space(10));

        LinearLayout magir=card(root);
        magir.addView(text("🎙 МИРЫ МАГИРА",19,WHITE,true));
        magir.addView(text("Отдельная запись по 5 минут в каждом файле. " +
            "Видимое уведомление и работа при блокировке экрана.",13,SUB,false),space(7));
        record=button("🎙 Начать запись «Магира»",true,v->toggleRecording());
        magir.addView(record,space(12));
        magirState=text("",13,SUB,false);magir.addView(magirState,space(8));
        magir.addView(button("📂 Выбрать старую игровую сессию",false,v->chooseSession()),space(10));
        selectedSessionInfo=text("",13,SUB,false);
        magir.addView(selectedSessionInfo,space(7));
        magir.addView(button("📥 Импортировать сохранённый ZIP",false,v->selectSessionArchive()),space(8));
        transcribe=button("📝 Точная расшифровка Whisper (русский)",false,
            v->startTranscriptionWithConsent());
        magir.addView(transcribe,space(13));
        magir.addView(button("⚙ Не удалось скачать модель? Исправить",false,
            v->showModelRecovery()),space(8));
        asrState=text("",13,SUB,false);magir.addView(asrState,space(7));
        txt=button("📄 Сохранить расшифровку TXT",false,v->saveTranscript());
        magir.addView(txt,space(13));
        zip=button("🗂 Сохранить аудио + текст ZIP",false,v->saveSessionArchive());
        magir.addView(zip,space(8));
        magir.addView(text("В другой чат «Магира» можно загрузить TXT или ZIP после экспорта. " +
            "Запись не отправляется автоматически.",12,SUB,false),space(8));
        root.addView(text("Разрешение на уведомления Android широкое — наш код читает " +
            "только медиасессии поддерживаемых приложений. Запись требует микрофон; " +
            "загрузка модели речи — Интернет только по твоему согласию. " +
            "Модель Whisper Small ~488 МБ, распознавание может занять много времени.",
            12,SUB,false),space(19));
    }

    private boolean hasListener(){
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        return n!=null&&n.isNotificationListenerAccessGranted(
            new ComponentName(this,MusicListenerService.class));
    }
    private void toggleMusic(){
        if(TrackStore.active(this)){TrackStore.stop(this);update();return;}
        if(hasListener()){
            TrackStore.start(this);MediaScan.read(this);update();return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Разрешение Android")
            .setMessage("Для автоматического получения событий MediaSession Android требует " +
                "широкий доступ к уведомлениям, включая сообщения других приложений. " +
                "Наш код отбирает только медиаданные Яндекс Музыки и выбранных " +
                "видеоплееров и не пересылает уведомления. " +
                "Разрешай доступ, только если согласна с таким разрешением.")
            .setPositiveButton("Открыть настройки",(d,w)->{
                pendingListen=true;startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }).setNegativeButton("Отмена",null).show();
    }
    private void copyDiary(){
        String msg="Чанти, это мой музыкальный дневник. Музыка обсуждается В ЭТОМ чате. " +
            "Быстрое переключение не равно дизлайку. Не выдумывай слова песен.\n\n" +
            TrackStore.report(this)+"\n"+VideoStore.report(this);
        ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(cb!=null)cb.setPrimaryClip(ClipData.newPlainText("Дневник для Чанти",msg));
        new AlertDialog.Builder(this).setTitle("Скопировано ❤️")
            .setMessage("Вставь текст в этот разговор ChatGPT. Автоматическая доставка " +
                "именно сюда пока не поддерживается.")
            .setPositiveButton("Открыть ChatGPT",(d,w)->openChat())
            .setNegativeButton("Закрыть",null).show();
    }
    private void openChat(){
        Intent i=getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
        if(i!=null)startActivity(i);
        else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://chatgpt.com")));
    }

    private android.content.SharedPreferences recordPrefs(){
        return getSharedPreferences(MagirRecorderService.PREF,MODE_PRIVATE);
    }
    private boolean recording(){
        return MagirRecorderService.isRecording();
    }
    private File sessionRoot(){
        File music=getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        return music==null?null:new File(music,"Magir");
    }
    private boolean validSession(File f){
        File root=sessionRoot();
        if(root==null||f==null)return false;
        try{
            return f.isDirectory()&&f.getCanonicalFile().getParentFile()
                .equals(root.getCanonicalFile());
        }catch(Exception e){return false;}
    }
    private File[] allSessions(){
        File root=sessionRoot();
        if(root==null)return new File[0];
        File[] list=root.listFiles(f->f.isDirectory()&&f.getName().startsWith("Magir_"));
        if(list==null)return new File[0];
        Arrays.sort(list,(a,b)->b.getName().compareTo(a.getName()));
        return list;
    }
    private File sessionDir(){
        String selected=recordPrefs().getString("selected_session","");
        File preferred=selected.isEmpty()?null:new File(selected);
        if(validSession(preferred))return preferred;
        String recent=recordPrefs().getString("last_session","");
        File last=recent.isEmpty()?null:new File(recent);
        if(validSession(last))return last;
        File[] all=allSessions();
        return all.length==0?null:all[0];
    }
    private void chooseSession(){
        if(recording()){
            Toast.makeText(this,"Останови запись перед выбором сессии",Toast.LENGTH_LONG).show();
            return;
        }
        File[] dirs=allSessions();
        if(dirs.length==0){
            Toast.makeText(this,"Сессии пока не найдены",Toast.LENGTH_LONG).show();return;
        }
        String[] names=new String[dirs.length];
        for(int i=0;i<dirs.length;i++){
            File[] audio=dirs[i].listFiles((p,n)->n.matches("part_\\d{4}\\.m4a"));
            File[] completed=dirs[i].listFiles((p,n)->n.matches("part_\\d{4}\\.whisper.txt"));
            names[i]=dirs[i].getName().replace("Magir_","")+
                " · аудио "+(audio==null?0:audio.length)+
                " · расшифровано "+(completed==null?0:completed.length);
        }
        new AlertDialog.Builder(this).setTitle("Выбери игровую сессию")
            .setItems(names,(dialog,which)->{
                recordPrefs().edit().putString("selected_session",
                    dirs[which].getAbsolutePath()).apply();
                update();
            })
            .setNegativeButton("Отмена",null).show();
    }
    private void selectSessionArchive(){
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("application/zip");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick,SESSION_IMPORT_REQUEST);
    }
    private void importSessionArchive(Uri uri){
        new Thread(()->{
            File destination=null;
            try{
                File root=sessionRoot();
                if(root==null)throw new IllegalStateException("Нет хранилища");
                if(!root.exists()&&!root.mkdirs())throw new IllegalStateException("Нет папки");
                String stamp=new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    Locale.US).format(new java.util.Date());
                destination=new File(root,"Magir_import_"+stamp);
                if(!destination.mkdir())throw new IllegalStateException("Не создать папку");
                long total=0;
                int count=0;
                try(java.util.zip.ZipInputStream stream=new java.util.zip.ZipInputStream(
                    getContentResolver().openInputStream(uri))){
                    java.util.zip.ZipEntry item;
                    byte[] bytes=new byte[65536];
                    while((item=stream.getNextEntry())!=null){
                        String name=item.getName();
                        if(item.isDirectory()||name.contains("/")||name.contains("\\")
                            ||!(name.matches("part_\\d{4}\\.m4a") ||
                                name.matches("part_\\d{4}\\.whisper.txt") ||
                                name.equals("transcript_whisper_ru.txt")))continue;
                        if(++count>200)throw new IllegalStateException("Слишком много файлов");
                        File output=new File(destination,name);
                        try(FileOutputStream out=new FileOutputStream(output)){
                            int n;
                            while((n=stream.read(bytes))!=-1){
                                if(n==0)continue;
                                total+=n;
                                if(total>1500L*1024*1024)
                                    throw new IllegalStateException("Архив слишком велик");
                                out.write(bytes,0,n);
                            }
                        }
                        stream.closeEntry();
                    }
                }
                if(count==0)throw new IllegalStateException("Нет файлов Магира");
                recordPrefs().edit().putString("selected_session",
                    destination.getAbsolutePath()).apply();
                runOnUiThread(()->{
                    Toast.makeText(this,"Сессия импортирована",Toast.LENGTH_LONG).show();
                    update();
                });
            }catch(Exception ex){
                if(destination!=null){
                    File[] files=destination.listFiles();
                    if(files!=null)for(File f:files)f.delete();
                    destination.delete();
                }
                String message=ex.getMessage();
                runOnUiThread(()->Toast.makeText(this,
                    "Ошибка импорта: "+message,Toast.LENGTH_LONG).show());
            }
        },"Magir-import").start();
    }
    private File[] parts(){
        File dir=sessionDir();
        if(dir==null)return new File[0];
        File[] files=dir.listFiles((p,name)->name.matches("part_\\d{4}\\.m4a"));
        if(files==null)return new File[0];
        Arrays.sort(files,Comparator.comparing(File::getName));
        return files;
    }

    private void toggleRecording(){
        if(recording()){
            Intent stop=new Intent(this,MagirRecorderService.class).setAction(MagirRecorderService.STOP);
            startService(stop);
            handler.postDelayed(this::update,800);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Запись игроков")
            .setMessage("Перед записью убедись, что все участники согласились. " +
                "Будет включён микрофон. Каждые пять минут — новый отдельный файл, " +
                "чтобы не потерять целую игру при сбое.")
            .setPositiveButton("Начать",(d,w)->{
                if(Build.VERSION.SDK_INT>=23
                    &&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);
                else startRecording();
            }).setNegativeButton("Отмена",null).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] perm,int[] grant){
        super.onRequestPermissionsResult(request,perm,grant);
        if(request==MIC_REQUEST&&grant.length>0&&grant[0]==PackageManager.PERMISSION_GRANTED)
            startRecording();
    }
    private void startRecording(){
        try{
            recordPrefs().edit().remove("selected_session").apply();
            Intent i=new Intent(this,MagirRecorderService.class).setAction(MagirRecorderService.START);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            handler.postDelayed(this::update,900);
        }catch(Exception e){
            Toast.makeText(this,"Запись не началась: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void startTranscriptionWithConsent(){
        if(recording()){
            Toast.makeText(this,"Сначала останови запись",Toast.LENGTH_LONG).show();return;
        }
        if(parts().length==0){
            Toast.makeText(this,"Нет сохранённых частей записи",Toast.LENGTH_LONG).show();return;
        }
        if(WhisperModelInstaller.installed(this)){launchTranscriber();return;}
        new AlertDialog.Builder(this).setTitle("Точная русская расшифровка Whisper")
            .setMessage("Загрузить проверенную модель Whisper Small (~488 МБ) с Hugging Face? " +
                "Скачивание один раз и только по твоему согласию. Затем весь звук обрабатывается на телефоне. " +
                "Понадобится достаточно памяти, свободного места и времени. " +
                "Модель не различает игроков и может ошибаться в магических именах.")
            .setPositiveButton("Скачать и расшифровать",(d,w)->launchTranscriber())
            .setNegativeButton("Отмена",null).show();
    }
    private void showModelRecovery(){
        new AlertDialog.Builder(this)
            .setTitle("Не удалось скачать Whisper?")
            .setMessage("Можно повторить загрузку позже либо скачать ggml-small.bin " +
                "в браузере и выбрать его здесь. Приложение проверит размер и SHA-256. " +
                "Поддельная модель установлена не будет.")
            .setPositiveButton("Выбрать файл",(d,w)->pickModel())
            .setNeutralButton("Скачать в браузере",(d,w)->openModelInBrowser())
            .setNegativeButton("Отмена",null).show();
    }
    private void pickModel(){
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("*/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(pick,MODEL_IMPORT_REQUEST);}
        catch(Exception e){Toast.makeText(this,"Не открыть выбор файла",Toast.LENGTH_LONG).show();}
    }
    private void openModelInBrowser(){
        try{startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse(WhisperModelInstaller.PUBLIC_MODEL_URL)));}
        catch(Exception e){Toast.makeText(this,"Не удалось открыть браузер",Toast.LENGTH_LONG).show();}
    }
    private void importSelectedModel(Uri uri){
        final android.content.SharedPreferences prefs =
            getSharedPreferences(MagirWhisperService.PREF,MODE_PRIVATE);
        prefs.edit().putString("status","Импортирую модель Whisper…").apply();
        new Thread(()->{
            try{
                WhisperModelInstaller.importFromUri(this,uri,
                    message->prefs.edit().putString("status",message).apply());
                runOnUiThread(()->{
                    Toast.makeText(this,"Модель проверена! Запускаю расшифровку.",
                        Toast.LENGTH_LONG).show();
                    launchTranscriber();
                });
            }catch(Exception error){
                String message=WhisperModelInstaller.readable(error);
                prefs.edit().putString("status","Ошибка модели: "+message).apply();
                runOnUiThread(()->new AlertDialog.Builder(this)
                    .setTitle("Не удалось установить модель")
                    .setMessage(message).setPositiveButton("Понятно",null).show());
            }
        },"Whisper-model-import").start();
    }

    private void launchTranscriber(){
        try{
            File target=sessionDir();
            if(!validSession(target))throw new IllegalStateException("Сессия не выбрана");
            Intent i=new Intent(this,MagirWhisperService.class).setAction(MagirWhisperService.START);
            i.putExtra("session_dir",target.getAbsolutePath());
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            handler.postDelayed(this::update,1000);
        }catch(Exception e){
            Toast.makeText(this,"Не удалось запустить распознавание",Toast.LENGTH_LONG).show();
        }
    }

    private void saveSessionArchive(){
        if(recording()){
            Toast.makeText(this,"Останови запись перед экспортом",Toast.LENGTH_LONG).show();return;
        }
        if(parts().length==0){
            Toast.makeText(this,"Нет записанных фрагментов",Toast.LENGTH_LONG).show();return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("export_session",
            sessionDir().getAbsolutePath()).apply();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE,sessionDir().getName()+".zip");
        startActivityForResult(i,ZIP_REQUEST);
    }
    private void saveTranscript(){
        File dir=sessionDir();
        if(dir==null||!new File(dir,"transcript_whisper_ru.txt").isFile()){
            Toast.makeText(this,"Сначала запусти расшифровку",Toast.LENGTH_LONG).show();return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("export_session",
            dir.getAbsolutePath()).apply();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE,dir.getName()+"_transcript.txt");
        startActivityForResult(i,TEXT_REQUEST);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(req==SESSION_IMPORT_REQUEST){
            importSessionArchive(data.getData());
            return;
        }
        if(req==MODEL_IMPORT_REQUEST){
            importSelectedModel(data.getData());
            return;
        }
        if(req!=ZIP_REQUEST&&req!=TEXT_REQUEST)return;
        Uri location=data.getData();
        String exportPath=getPreferences(MODE_PRIVATE).getString("export_session","");
        File dir=exportPath.isEmpty()?null:new File(exportPath);
        if(!validSession(dir))return;
        boolean zipMode=req==ZIP_REQUEST;
        Toast.makeText(this,"Сохраняю файл…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            try(OutputStream out=getContentResolver().openOutputStream(location)){
                if(out==null)throw new IllegalStateException("Не открыть выбранный файл");
                if(!zipMode){
                    copy(new File(dir,"transcript_whisper_ru.txt"),out);
                }else{
                    try(ZipOutputStream z=new ZipOutputStream(out)){
                        File[] all=dir.listFiles((p,name)->name.matches("part_\\d{4}\\.m4a")
                                ||name.equals("transcript_whisper_ru.txt")
                                ||name.matches("part_\\d{4}\\.whisper.txt"));
                        if(all==null||all.length==0)throw new IllegalStateException("Нет файлов");
                        Arrays.sort(all,Comparator.comparing(File::getName));
                        for(File f:all){
                            if(!f.isFile())continue;
                            z.putNextEntry(new ZipEntry(f.getName()));
                            copy(f,z);
                            z.closeEntry();
                        }
                        z.finish();
                    }
                }
                runOnUiThread(()->Toast.makeText(this,"Готово! Файл можно отправить в чат «Магира».",
                    Toast.LENGTH_LONG).show());
            }catch(Exception e){
                runOnUiThread(()->Toast.makeText(this,"Ошибка сохранения: "+e.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
        },"Magir-export").start();
    }
    private static void copy(File src,OutputStream dst)throws Exception{
        try(FileInputStream in=new FileInputStream(src)){
            byte[] bytes=new byte[65536];int read;
            while((read=in.read(bytes))!=-1)dst.write(bytes,0,read);
        }
    }
    private void update(){
        if(listen==null)return;
        TrackStore.Snapshot song=TrackStore.snapshot(this);
        listen.setText(song.active?"■ Завершить прослушивание":"▶ Чанти, слушаем!");
        listenerStatus.setText(hasListener()?"✓ Доступ к медиасессиям включён":
            "Для автоматического режима нужно однократное разрешение Android");
        musicTitle.setText(song.title.isEmpty()?(song.active?"Жду музыку…":"Сейчас не слушаем"):song.title);
        musicArtist.setText(song.artist);
        musicStatus.setText(song.active?(song.playing?
            "● Сейчас играет · "+Math.round(song.heardMs/1000.0)+" с":"○ На паузе или жду плеер"):
            "Музыкальная сессия выключена");
        videoStatus.setText(VideoStore.current(this));

        boolean r=recording();
        record.setText(r?"■ Остановить запись «Магира»":"🎙 Начать запись «Магира»");
        int finished=recordPrefs().getInt("chunks_completed",0);
        String lastError=recordPrefs().getString("last_error","");
        long started=recordPrefs().getLong("started_at",0);
        magirState.setText(r?
            "● Записываю · готовых фрагментов: "+finished+
             " · "+Math.max(0,(System.currentTimeMillis()-started)/60000)+" мин":
            !lastError.isEmpty()?lastError:
            "Запись выключена · файлов: "+parts().length);
        File chosen=sessionDir();
        if(selectedSessionInfo!=null){
            File[] selectedParts=parts();
            File[] done=chosen==null?null:chosen.listFiles(
                (p,n)->n.matches("part_\\d{4}\\.whisper.txt"));
            selectedSessionInfo.setText(chosen==null?"Нет сохранённой сессии":
                "Выбрана: "+chosen.getName()+
                " · аудио: "+selectedParts.length+
                " · готовых частей текста: "+(done==null?0:done.length));
        }
        String asrText=getSharedPreferences(MagirWhisperService.PREF,MODE_PRIVATE)
            .getString("status","Whisper Small ещё не загружена");
        asrState.setText(asrText);
        boolean has=parts().length>0;
        transcribe.setEnabled(!r&&has);
        zip.setEnabled(!r&&has);
        File dir=sessionDir();
        txt.setEnabled(!r&&dir!=null&&new File(dir,"transcript_whisper_ru.txt").isFile());
    }
}
