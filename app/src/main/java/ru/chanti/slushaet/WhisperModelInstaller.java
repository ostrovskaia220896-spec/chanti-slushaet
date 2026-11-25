package ru.chanti.slushaet;

import android.content.Context;
import android.os.StatFs;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Resumable consent-based download, verified against the published SHA256 of ggml-small.bin.
 * No user speech is uploaded. Downloaded model is 487601967 bytes (Whisper multilingual small).
 */
final class WhisperModelInstaller {
    private static final String URL_MODEL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin";
    private static final String SHA256 =
        "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b";
    private static final long EXPECTED=487601967L;
    private static final long MAX=600L*1024*1024;
    interface Status { void update(String text); }
    private WhisperModelInstaller(){}

    static File file(Context ctx){
        return new File(new File(ctx.getFilesDir(),"whisper"),"ggml-small.bin");
    }
    static boolean installed(Context ctx){
        File f=file(ctx);
        return f.isFile()&&f.length()==EXPECTED &&
            ctx.getSharedPreferences("whisper_model_v06",Context.MODE_PRIVATE)
                .getBoolean("verified",false);
    }
    static File install(Context ctx,Status listener)throws Exception{
        File finalFile=file(ctx);
        if(installed(ctx))return finalFile;
        File folder=finalFile.getParentFile();
        if(!folder.exists()&&!folder.mkdirs())throw new IllegalStateException("Нет папки модели");
        File partial=new File(folder,"ggml-small.bin.download");
        if(partial.length()>EXPECTED)partial.delete();
        long resume=partial.exists()?partial.length():0;
        StatFs stats=new StatFs(folder.getAbsolutePath());
        long available=stats.getAvailableBytes();
        if(available<EXPECTED-resume+150L*1024*1024)
            throw new IllegalStateException("Нужно около 700 МБ свободного места");

        listener.update("Загружаю Whisper Small (~488 МБ), можно продолжить при обрыве сети");
        HttpURLConnection c=(HttpURLConnection)new URL(URL_MODEL).openConnection();
        c.setConnectTimeout(25000);c.setReadTimeout(90000);
        c.setRequestProperty("User-Agent","ChantiSlushaet/0.6");
        c.setRequestProperty("Accept-Encoding","identity");
        if(resume>0)c.setRequestProperty("Range","bytes="+resume+"-");
        c.connect();
        int code=c.getResponseCode();
        boolean append=resume>0&&code==206;
        if(code!=200&&code!=206) {
            c.disconnect();
            throw new IllegalStateException("Не удалось скачать модель (HTTP "+code+")");
        }
        if(code==206&&!append){
            c.disconnect();throw new IllegalStateException("Неправильный ответ сервера на докачку");
        }
        if(code==206){
            String cr=c.getHeaderField("Content-Range");
            if(cr==null||!cr.startsWith("bytes "+resume+"-")){
                c.disconnect();throw new IllegalStateException("Сервер вернул чужой диапазон");
            }
        }
        if(!append)resume=0;
        long total=resume,lastUpdate=0;
        try(BufferedInputStream in=new BufferedInputStream(c.getInputStream());
            FileOutputStream out=new FileOutputStream(partial,append)){
            byte[] buffer=new byte[128*1024];int n;
            while((n=in.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(n==0)continue;
                total+=n;
                if(total>MAX)throw new IllegalStateException("Размер модели не совпадает");
                out.write(buffer,0,n);
                long now=System.currentTimeMillis();
                if(now-lastUpdate>2500){
                    listener.update(String.format(Locale.US,"Модель Whisper: %d / 488 МБ",
                        total/(1024*1024)));
                    lastUpdate=now;
                }
            }
            out.getFD().sync();
        }finally{c.disconnect();}
        if(partial.length()!=EXPECTED)
            throw new IllegalStateException("Модель загружена не полностью: "+
                partial.length()+"/"+EXPECTED+" байт. Загрузка продолжится при повторе.");
        listener.update("Проверяю контрольную сумму модели…");
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(partial))){
            byte[] buffer=new byte[128*1024];int n;
            while((n=in.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                sha.update(buffer,0,n);
            }
        }
        StringBuilder hash=new StringBuilder();
        for(byte b:sha.digest())hash.append(String.format(Locale.US,"%02x",b&255));
        if(!SHA256.equalsIgnoreCase(hash.toString())){
            partial.delete();
            throw new SecurityException("Контрольная сумма не совпадает; файл удалён");
        }
        if(finalFile.exists())finalFile.delete();
        if(!partial.renameTo(finalFile))throw new IllegalStateException("Не удалось сохранить модель");
        ctx.getSharedPreferences("whisper_model_v06",Context.MODE_PRIVATE)
            .edit().putBoolean("verified",true).apply();
        listener.update("Модель готова. Распознавание будет локальным.");
        return finalFile;
    }
}
