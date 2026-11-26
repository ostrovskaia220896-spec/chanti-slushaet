package ru.chanti.slushaet;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** Verified Whisper Small download, resumable retry, optional secure file import. */
final class WhisperModelInstaller {
    static final String PUBLIC_MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin?download=true";
    private static final String[] SOURCES={
        PUBLIC_MODEL_URL,
        "https://huggingface.co/cgerdes/whisper.cpp-mirror/resolve/main/ggml-small.bin?download=true"
    };
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
            ctx.getSharedPreferences("whisper_model_v10",Context.MODE_PRIVATE)
                .getBoolean("verified",false);
    }
    private static File folder(Context ctx)throws Exception{
        File parent=file(ctx).getParentFile();
        if(!parent.exists()&&!parent.mkdirs())throw new IllegalStateException("Не создать папку модели");
        StatFs stats=new StatFs(parent.getAbsolutePath());
        if(stats.getAvailableBytes()<160L*1024*1024)
            throw new IllegalStateException("Недостаточно памяти телефона");
        return parent;
    }
    static File install(Context ctx,Status status)throws Exception{
        if(installed(ctx))return file(ctx);
        File parent=folder(ctx);
        File partial=new File(parent,"ggml-small.bin.download");
        if(partial.length()>EXPECTED)partial.delete();
        if(partial.length()==EXPECTED)
            return verifyAndInstall(ctx,partial,status);
        Exception last=null;
        for(int i=0;i<SOURCES.length;i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            status.update(i==0?"Загружаю Whisper Small (488 МБ)…":
                "Первый сервер не ответил. Пробую запасной источник модели…");
            try{
                fetch(SOURCES[i],partial,status);
                return verifyAndInstall(ctx,partial,status);
            }catch(InterruptedException e){throw e;}
            catch(SecurityException e){throw e;}
            catch(Exception e){
                last=e;
                status.update("Сервер "+(i+1)+": "+readable(e)+
                    (i+1<SOURCES.length?". Пробую другой…":""));
            }
        }
        throw new IllegalStateException("Не удалось загрузить модель: "+
                (last==null?"сеть недоступна":readable(last))+
                ". Можно скачать файл через браузер и выбрать его в приложении.");
    }
    private static void fetch(String source,File partial,Status status)throws Exception{
        long have=partial.length();
        HttpURLConnection connection=(HttpURLConnection)new URL(source).openConnection();
        connection.setConnectTimeout(25000);
        connection.setReadTimeout(90000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent","ChantiSlushaet/1.0.0 (Android)");
        connection.setRequestProperty("Accept-Encoding","identity");
        if(have>0)connection.setRequestProperty("Range","bytes="+have+"-");
        try{
            int code=connection.getResponseCode();
            if(code!=200&&code!=206)
                throw new java.io.IOException("Сервер ответил HTTP "+code);
            boolean append=have>0&&code==206;
            if(code==206){
                String range=connection.getHeaderField("Content-Range");
                if(range==null||!range.startsWith("bytes "+have+"-"))
                    throw new java.io.IOException("Сервер не поддерживает корректную докачку");
            }
            long total=append?have:0;
            long last=0;
            try(InputStream in=new BufferedInputStream(connection.getInputStream(),65536);
                FileOutputStream out=new FileOutputStream(partial,append)){
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(n==0)continue;
                    total+=n;
                    if(total>MAX)throw new java.io.IOException("Скачан файл слишком большого размера");
                    out.write(buffer,0,n);
                    long now=System.currentTimeMillis();
                    if(now-last>2000){
                        status.update("Загружено "+(total/1024/1024)+" из 465 МиБ");
                        last=now;
                    }
                }
                out.getFD().sync();
            }
            if(partial.length()!=EXPECTED)
                throw new java.io.IOException("Загрузка оборвалась на "+
                    (partial.length()/1024/1024)+" МиБ; можно продолжить");
        }finally{connection.disconnect();}
    }
    static File importFromUri(Context ctx,Uri uri,Status status)throws Exception{
        if(uri==null)throw new IllegalArgumentException("Файл не выбран");
        File destination=folder(ctx);
        File candidate=new File(destination,"ggml-small.import");
        status.update("Проверяю выбранную модель Whisper…");
        long copied=0;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=ctx.getContentResolver().openInputStream(uri);
            FileOutputStream out=new FileOutputStream(candidate,false)){
            if(input==null)throw new java.io.IOException("Не прочитать выбранный файл");
            byte[] block=new byte[65536];int n;
            while((n=input.read(block))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(n==0)continue;
                copied+=n;
                if(copied>MAX)throw new java.io.IOException("Этот файл слишком большой");
                out.write(block,0,n);
                digest.update(block,0,n);
            }
            out.getFD().sync();
        }catch(Exception ex){candidate.delete();throw ex;}
        if(copied!=EXPECTED){
            candidate.delete();
            throw new java.io.IOException("Неверный размер модели. Нужен ggml-small.bin (488 МБ)");
        }
        StringBuilder actual=new StringBuilder();
        for(byte b:digest.digest())actual.append(String.format(Locale.US,"%02x",b&255));
        if(!SHA256.equalsIgnoreCase(actual.toString())){
            candidate.delete();
            throw new SecurityException("Не та модель Whisper (контрольная сумма не совпала)");
        }
        return finish(ctx,candidate,status);
    }
    private static File verifyAndInstall(Context ctx,File partial,Status status)throws Exception{
        status.update("Модель загружена. Проверяю подлинность файла…");
        if(partial.length()!=EXPECTED)
            throw new java.io.IOException("Модель скачана не полностью");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new BufferedInputStream(new FileInputStream(partial))){
            byte[] block=new byte[65536];int n;
            while((n=in.read(block))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                digest.update(block,0,n);
            }
        }
        StringBuilder actual=new StringBuilder();
        for(byte b:digest.digest())actual.append(String.format(Locale.US,"%02x",b&255));
        if(!SHA256.equalsIgnoreCase(actual.toString())){
            partial.delete();
            throw new SecurityException("Скачанный файл не прошёл проверку SHA-256");
        }
        return finish(ctx,partial,status);
    }
    private static File finish(Context ctx,File source,Status status)throws Exception{
        File target=file(ctx);
        if(target.exists()&&!target.delete())
            throw new java.io.IOException("Нельзя заменить старую модель");
        if(!source.renameTo(target))
            throw new java.io.IOException("Не удалось сохранить модель на телефоне");
        ctx.getSharedPreferences("whisper_model_v10",Context.MODE_PRIVATE)
            .edit().putBoolean("verified",true).apply();
        status.update("Модель Whisper проверена и установлена.");
        return target;
    }
    static String readable(Exception e){
        String message=e.getMessage()==null?"":e.getMessage().trim();
        if(e instanceof java.net.UnknownHostException)
            return "нет доступа к серверу — проверь интернет и VPN";
        if(e instanceof java.net.SocketTimeoutException)
            return "истекло время ожидания сервера";
        if(e instanceof java.net.SocketException)
            return "соединение прервалось";
        if(e instanceof java.net.UnknownServiceException)
            return "сервер не поддерживает соединение";
        return message.isEmpty()?e.getClass().getSimpleName():message;
    }
}
