package ru.chanti.slushaet;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads one open-source model after explicit user approval; offline thereafter. */
final class VoskModelInstaller {
    private static final String URL_MODEL =
        "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip";
    private static final String MODEL_NAME="vosk-model-small-ru-0.22";
    private static final long MAX_ZIP=80L*1024*1024;
    private static final long MAX_EXTRACT=650L*1024*1024;
    interface Status { void report(String text); }

    static File modelDir(Context c){
        return new File(new File(c.getFilesDir(),"speech_models"),MODEL_NAME);
    }
    static boolean available(Context c){
        File m=modelDir(c);
        return new File(m,".complete").isFile()
            && new File(m,"am").isDirectory() && new File(m,"conf").isDirectory();
    }

    static File ensure(Context ctx,Status status) throws Exception{
        if(available(ctx))return modelDir(ctx);
        File base=new File(ctx.getFilesDir(),"speech_models");
        if(!base.exists()&&!base.mkdirs())throw new IllegalStateException("Нет папки для модели");
        File zip=new File(ctx.getCacheDir(),MODEL_NAME+".zip.part");
        File dir=modelDir(ctx);
        if(dir.exists())remove(dir);
        status.report("Загрузка русской модели (~45 МБ) по HTTPS…");
        HttpURLConnection conn=(HttpURLConnection)new URL(URL_MODEL).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        try{
            if(conn.getResponseCode()!=200)throw new IllegalStateException("HTTP "+conn.getResponseCode());
            long size=conn.getContentLengthLong();
            if(size>MAX_ZIP)throw new IllegalStateException("Неверный размер модели");
            long total=0;long latest=0;
            try(BufferedInputStream in=new BufferedInputStream(conn.getInputStream());
                FileOutputStream out=new FileOutputStream(zip)){
                byte[] data=new byte[32*1024];int n;
                while((n=in.read(data))>=0){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(n==0)continue;
                    total+=n;
                    if(total>MAX_ZIP)throw new IllegalStateException("Файл модели слишком велик");
                    out.write(data,0,n);
                    if(System.currentTimeMillis()-latest>1500){
                        status.report("Скачано "+(total/1024/1024)+" МБ…");latest=System.currentTimeMillis();
                    }
                }
            }
        }finally{conn.disconnect();}
        status.report("Распаковываю модель на телефоне…");
        int files=0;long unpacked=0;
        try(ZipInputStream in=new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))){
            ZipEntry e;byte[] buffer=new byte[32*1024];
            String baseCanonical=base.getCanonicalPath()+File.separator;
            while((e=in.getNextEntry())!=null){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                File dest=new File(base,e.getName());
                String canonical=dest.getCanonicalPath();
                if(!canonical.startsWith(baseCanonical))throw new SecurityException("Недопустимый путь ZIP");
                files++;if(files>25000)throw new SecurityException("Слишком много файлов");
                if(e.isDirectory()){
                    if(!dest.exists()&&!dest.mkdirs())throw new IllegalStateException("Не удалось создать папку");
                }else{
                    File parent=dest.getParentFile();
                    if(parent!=null&&!parent.exists()&&!parent.mkdirs())
                        throw new IllegalStateException("Не удалось создать папку модели");
                    try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
                        int n;
                        while((n=in.read(buffer))>0){
                            unpacked+=n;
                            if(unpacked>MAX_EXTRACT)throw new SecurityException("Слишком большой ZIP");
                            out.write(buffer,0,n);
                        }
                    }
                }
                in.closeEntry();
            }
        }catch(Exception ex){remove(dir);throw ex;}
        finally{zip.delete();}
        if(!new File(dir,"am").isDirectory()||!new File(dir,"conf").isDirectory()){
            remove(dir);throw new IllegalStateException("Модель не распакована");
        }
        if(!new File(dir,".complete").createNewFile()&&!available(ctx))
            throw new IllegalStateException("Ошибка проверки модели");
        status.report("Модель установлена. Теперь распознавание работает офлайн.");
        return dir;
    }
    private static void remove(File f){
        if(f.isDirectory()){
            File[] list=f.listFiles();
            if(list!=null)for(File x:list)remove(x);
        }
        f.delete();
    }
    private VoskModelInstaller(){}
}
