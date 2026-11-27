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
    private static final int MIC_REQUEST=1801,ZIP_REQUEST=1802,TEXT_REQUEST=1803,MODEL_IMPORT_REQUEST=1804,
        ZIP_IMPORT_REQUEST=1805,TXT_IMPORT_REQUEST=1806;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView musicTitle,musicArtist,musicStatus,listenerStatus,videoStatus;
    private TextView magirState,asrState,selectedState;
    private Button listen,record,transcribe,zip,txt,chooser;
    private File pendingExportSession;
    private File pendingTextImportSession;
    private boolean pendingListen=false;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){update();handler.postDelayed(this,1300);}
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        TrackStore.recoverOutbox(this);
        MagirSessions.recover(this);
        MagirRecorderService.reconcile(this);
        MagirWhisperService.reconcile(this);
        draw();
    }
    @Override protected void onResume(){
        super.onResume();
        if(pendingListen&&hasListener()){
            pendingListen=false;
            MediaScan.resetSources();TrackStore.start(this);MediaScan.read(this);
        }
        MediaScan.read(this);
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
        root.addView(text("🎧 Чанти слушает",26,WHITE,true));
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
        music.addView(text("События сохраняются в локальной очереди на телефоне. " +
            "Прямая доставка в существующий чат ещё подключается.",12,SUB,false),space(9));

        LinearLayout video=card(root);
        video.addView(text("📺 ВИДЕО · проверка медиаданных",18,WHITE,true));
        video.addView(text("YouTube, Кинопоиск, Иви — если плеер публикует название. " +
            "Само видео приложение не видит.",13,SUB,false),space(7));
        videoStatus=text("",14,ACCENT,false);video.addView(videoStatus,space(10));

        LinearLayout magir=card(root);
        magir.addView(text("🎙 МИРЫ МАГИРА",19,WHITE,true));
        magir.addView(text("Пять минут на фрагмент. Запись и расшифровка независимы; " +
            "можно ставить несколько сессий в очередь.",13,SUB,false),space(7));
        record=button("🎙 Начать запись «Магира»",true,v->toggleRecording());
        magir.addView(record,space(12));
        magirState=text("",13,SUB,false);magir.addView(magirState,space(8));
        chooser=button("📂 Выбрать запись",false,v->chooseSession());
        magir.addView(chooser,space(12));
        selectedState=text("",13,ACCENT,false);magir.addView(selectedState,space(6));
        magir.addView(button("⬇ Импортировать старый ZIP",false,v->pickSessionZip()),space(8));
        magir.addView(button("📄 Добавить старую расшифровку TXT",false,
            v->pickSessionTranscript()),space(8));
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
            MediaScan.resetSources();TrackStore.start(this);MediaScan.read(this);update();return;
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
    private File sessionDir(){return MagirSessions.selected(this);}
    private File[] parts(){return MagirSessions.audio(sessionDir());}
    private void chooseSession(){
        File[] all=MagirSessions.all(this);
        if(all.length==0){
            new AlertDialog.Builder(this).setTitle("Пока нет записей")
                .setMessage("Запиши новую игру либо импортируй старый ZIP.")
                .setPositiveButton("Хорошо",null).show();
            return;
        }
        String[] labels=new String[all.length];
        for(int j=0;j<all.length;j++)labels[j]=MagirSessions.label(all[j]);
        new AlertDialog.Builder(this).setTitle("Выбери запись «Магира»")
            .setItems(labels,(dialog,which)->{
                MagirSessions.setSelected(this,all[which]);
                update();
            }).setNegativeButton("Отмена",null).show();
    }
    private void pickSessionZip(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        startActivityForResult(i,ZIP_IMPORT_REQUEST);
    }
    private void pickSessionTranscript(){
        File dir=sessionDir();
        if(dir==null){Toast.makeText(this,"Сначала выбери запись",Toast.LENGTH_LONG).show();return;}
        pendingTextImportSession=dir;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        startActivityForResult(i,TXT_IMPORT_REQUEST);
    }
    private void importZip(Uri uri){
        selectedState.setText("Импортирую архив…");
        new Thread(()->{
            try{
                File done=MagirSessions.importZip(this,uri,message->
                    runOnUiThread(()->selectedState.setText(message)));
                runOnUiThread(()->{
                    Toast.makeText(this,"Готово! Восстановлено: "+MagirSessions.label(done),
                        Toast.LENGTH_LONG).show();
                    update();
                });
            }catch(Exception e){
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Ошибка импорта ZIP")
                    .setMessage(e.getMessage()).setPositiveButton("Понятно",null).show());
            }
        },"Magir-zip-import").start();
    }
    private void importTxt(Uri uri,File dir){
        if(dir==null)return;
        new Thread(()->{
            try{
                MagirSessions.importText(this,uri,dir);
                runOnUiThread(()->{Toast.makeText(this,"Текст добавлен к выбранной записи",
                    Toast.LENGTH_LONG).show();update();});
            }catch(Exception e){
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Ошибка импорта TXT")
                    .setMessage(e.getMessage()).setPositiveButton("Понятно",null).show());
            }
        },"Magir-text-import").start();
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
            Intent i=new Intent(this,MagirRecorderService.class).setAction(MagirRecorderService.START);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            handler.postDelayed(this::update,900);
        }catch(Exception e){
            Toast.makeText(this,"Запись не началась: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void startTranscriptionWithConsent(){
        File chosen=sessionDir();
        if(recording() && chosen!=null && chosen.getAbsolutePath().equals(
            recordPrefs().getString("last_session",""))){
            Toast.makeText(this,"Эта сессия ещё записывается. Выбери другую или останови запись.",
                Toast.LENGTH_LONG).show();return;
        }
        if(parts().length==0){
            Toast.makeText(this,"Нет завершённых фрагментов записи",Toast.LENGTH_LONG).show();return;
        }
        if(MagirSessions.fullyTranscribed(chosen)){
            Toast.makeText(this,"Эта запись уже полностью расшифрована",Toast.LENGTH_LONG).show();return;
        }
        if(MagirWhisperService.isQueuedOrRunning(this,chosen.getName())){
            Toast.makeText(this,"Эта запись уже в очереди или обрабатывается",Toast.LENGTH_LONG).show();return;
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
            File chosen=sessionDir();
            if(chosen==null)throw new IllegalStateException("Не выбрана запись");
            Intent i=new Intent(this,MagirWhisperService.class).setAction(MagirWhisperService.START);
            i.putExtra(MagirWhisperService.EXTRA_SESSION,chosen.getName());
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            handler.postDelayed(this::update,1000);
        }catch(Exception e){
            Toast.makeText(this,"Не удалось запустить распознавание",Toast.LENGTH_LONG).show();
        }
    }

    private void saveSessionArchive(){
        File chosen=sessionDir();
        if(recording() && chosen!=null && chosen.getAbsolutePath().equals(
            recordPrefs().getString("last_session",""))){
            Toast.makeText(this,"Эта запись ещё идёт. Экспортируй её после остановки.",
                Toast.LENGTH_LONG).show();return;
        }
        if(parts().length==0&&MagirSessions.pendingAudioCount(chosen)==0){
            Toast.makeText(this,"В этой записи пока нет аудиофрагментов",Toast.LENGTH_LONG).show();return;
        }
        pendingExportSession=sessionDir();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE,sessionDir().getName()+".zip");
        startActivityForResult(i,ZIP_REQUEST);
    }
    private void saveTranscript(){
        File dir=sessionDir();
        if(MagirSessions.transcript(dir)==null){
            Toast.makeText(this,"Сначала запусти расшифровку",Toast.LENGTH_LONG).show();return;
        }
        pendingExportSession=dir;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE,dir.getName()+"_transcript.txt");
        startActivityForResult(i,TEXT_REQUEST);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null){
            if(req==ZIP_REQUEST)pendingExportSession=null;
            if(req==TEXT_REQUEST)pendingExportSession=null;
            if(req==TXT_IMPORT_REQUEST)pendingTextImportSession=null;
            return;
        }
        if(req==MODEL_IMPORT_REQUEST){
            importSelectedModel(data.getData());return;
        }
        if(req==ZIP_IMPORT_REQUEST){
            importZip(data.getData());return;
        }
        if(req==TXT_IMPORT_REQUEST){
            File target=pendingTextImportSession;
            pendingTextImportSession=null;
            importTxt(data.getData(),target);return;
        }
        if(req!=ZIP_REQUEST&&req!=TEXT_REQUEST)return;
        Uri location=data.getData();
        File dir=pendingExportSession;
        pendingExportSession=null;
        if(dir==null)return;
        boolean zipMode=req==ZIP_REQUEST;
        Toast.makeText(this,"Сохраняю файл…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            try(OutputStream out=getContentResolver().openOutputStream(location)){
                if(out==null)throw new IllegalStateException("Не открыть выбранный файл");
                if(!zipMode){
                    File transcript=MagirSessions.transcript(dir);
                    if(transcript==null)throw new IllegalStateException("TXT не найден");
                    copy(transcript,out);
                }else{
                    try(ZipOutputStream z=new ZipOutputStream(out)){
                        File[] all=dir.listFiles((p,name)->MagirFileRules.isCompleteAudio(name)
                                ||MagirFileRules.isPendingAudio(name)
                                ||MagirFileRules.isPartTranscript(name)
                                ||MagirFileRules.isTimedSegments(name)
                                ||MagirFileRules.isSpeakerTurns(name)
                                ||MagirFileRules.isDiarizedTranscript(name)
                                ||name.equals("transcript_whisper_ru.txt")
                                ||name.equals("transcript_imported_ru.txt"));
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
        MagirRecorderService.reconcile(this);
        MagirWhisperService.reconcile(this);
        TrackStore.Snapshot song=TrackStore.snapshot(this);
        listen.setText(song.active?"■ Завершить прослушивание":"▶ Чанти, слушаем!");
        listenerStatus.setText(hasListener()?"✓ Доступ к медиасессиям включён":
            "Для автоматического режима нужно однократное разрешение Android");
        musicTitle.setText(song.title.isEmpty()?(song.active?"Жду музыку…":"Сейчас не слушаем"):song.title);
        musicArtist.setText(song.artist);
        String playbackStatus=song.active?(!song.fresh?
            "⚠ Нет свежих данных плеера · последнее название не подтверждено":
            song.playing?"● Сейчас играет · "+Math.round(song.heardMs/1000.0)+" с":
            "○ На паузе или жду плеер"):
            "Музыкальная сессия выключена";
        String outboxError=TrackStore.outboxError(this);
        String queueStatus=outboxError.isEmpty()?
            "Локальная очередь: "+TrackStore.outboxPending(this)+" событий":
            "Локальная очередь: ошибка сохранения ("+outboxError+")";
        musicStatus.setText(playbackStatus+"\n"+queueStatus);
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
            "Запись выключена · выбери нужную сессию ниже");
        File selectedForStatus=sessionDir();
        String asrText=getSharedPreferences(MagirWhisperService.PREF,MODE_PRIVATE)
            .getString(selectedForStatus==null?"status":
                "status_for_"+selectedForStatus.getName(),"Ещё не расшифровывали эту запись");
        asrState.setText(asrText);
        File dir=sessionDir();
        boolean has=MagirSessions.audio(dir).length>0;
        chooser.setText("📂 Выбрать запись ("+MagirSessions.all(this).length+")");
        selectedState.setText(dir==null?"Записей пока нет":MagirSessions.label(dir));
        boolean currentRecording=recording()&&dir!=null&&dir.getAbsolutePath().equals(
            recordPrefs().getString("last_session",""));
        boolean queued=dir!=null&&MagirWhisperService.isQueuedOrRunning(this,dir.getName());
        boolean done=MagirSessions.fullyTranscribed(dir);
        boolean busy=MagirWhisperService.isBusy(this);
        transcribe.setText(queued?"✓ В очереди / обрабатывается":
            currentRecording?"⏸ Сначала заверши эту запись":
            done?"✓ Все фрагменты расшифрованы":
            busy?"＋ Добавить в очередь":"📝 Точная расшифровка Whisper (русский)");
        transcribe.setEnabled(has&&!done&&!queued&&!currentRecording);
        zip.setEnabled((has||MagirSessions.pendingAudioCount(dir)>0)&&!currentRecording);
        txt.setEnabled(MagirSessions.transcript(dir)!=null);
    }
}
