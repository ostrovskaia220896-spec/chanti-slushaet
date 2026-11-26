package ru.chanti.slushaet;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Independent session selection and safe import of previously exported Magir ZIP recordings. */
final class MagirSessions {
    static final String PREF="magir_sessions_v08";
    static final String PART="part_\\d{4}\\.m4a";
    private static final int MAX_FILES=1500;
    private static final long MAX_BYTES=4L*1024*1024*1024;
    private MagirSessions(){}

    private static SharedPreferences pref(Context c){
        return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    static File root(Context c){
        File directory=c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        return directory==null?null:new File(directory,"Magir");
    }
    static File[] all(Context c){
        File base=root(c);
        if(base==null)return new File[0];
        File[] result=base.listFiles(f->f.isDirectory()&&f.getName().startsWith("Magir_")
            && !f.getName().endsWith(".partial"));
        if(result==null)return new File[0];
        Arrays.sort(result,(a,b)->b.getName().compareTo(a.getName()));
        return result;
    }
    static File find(Context c,String name){
        if(name==null||!name.matches("Magir_[a-zA-Z0-9_-]{6,80}"))return null;
        File base=root(c);
        if(base==null)return null;
        File f=new File(base,name);
        try{
            if(!f.getCanonicalFile().getParentFile().equals(base.getCanonicalFile()))
                return null;
        }catch(Exception e){return null;}
        return f.isDirectory()?f:null;
    }
    static File selected(Context c){
        String name=pref(c).getString("selected","");
        File chosen=find(c,name);
        if(chosen!=null)return chosen;
        File[] sessions=all(c);
        if(sessions.length==0)return null;
        setSelected(c,sessions[0]);
        return sessions[0];
    }
    static void setSelected(Context c,File dir){
        if(dir==null||find(c,dir.getName())==null)return;
        pref(c).edit().putString("selected",dir.getName()).apply();
    }
    static File[] audio(File dir){
        if(dir==null)return new File[0];
        File[] files=dir.listFiles((d,name)->name.matches(PART));
        if(files==null)return new File[0];
        Arrays.sort(files,Comparator.comparing(File::getName));
        return files;
    }
    static int completedTextParts(File dir){
        if(dir==null)return 0;
        File[] files=dir.listFiles((d,name)->name.matches("part_\\d{4}\\.whisper\\.txt"));
        return files==null?0:files.length;
    }
    static File transcript(File dir){
        if(dir==null)return null;
        File generated=new File(dir,"transcript_whisper_ru.txt");
        if(generated.isFile()&&generated.length()>0)return generated;
        File imported=new File(dir,"transcript_imported_ru.txt");
        return imported.isFile()&&imported.length()>0?imported:null;
    }
    static String label(File dir){
        int parts=audio(dir).length;
        int done=completedTextParts(dir);
        File txt=transcript(dir);
        return dir.getName().replace("Magir_","")+
            " · "+parts+" аудио · "+done+" обработано"+
            (txt!=null?" · TXT":"");
    }

    interface Progress { void report(String message); }
    private static String stamp(){
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
    }
    private static boolean accepted(String name){
        return name.matches(PART) || name.matches("part_\\d{4}\\.whisper\\.txt") ||
            name.equals("transcript_whisper_ru.txt") ||
            name.equals("transcript_imported_ru.txt");
    }

    /** Never overwrites old sessions or extracts a path from a ZIP entry. */
    static File importZip(Context c, Uri source, Progress progress)throws Exception{
        File base=root(c);
        if(base==null||(!base.exists()&&!base.mkdirs()))
            throw new IllegalStateException("Не удалось открыть хранилище записей");
        String id="Magir_import_"+stamp();
        File temp=new File(base,id+".partial");
        File dest=new File(base,id);
        if(!temp.mkdirs())throw new IllegalStateException("Не создать папку импорта");
        int count=0,audioCount=0;
        long total=0;
        try(InputStream input=c.getContentResolver().openInputStream(source)){
            if(input==null)throw new java.io.IOException("Не удалось прочитать ZIP");
            try(ZipInputStream zip=new ZipInputStream(input)){
                ZipEntry entry;
                byte[] buffer=new byte[65536];
                while((entry=zip.getNextEntry())!=null){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(entry.isDirectory()){zip.closeEntry();continue;}
                    String name=entry.getName();
                    if(!accepted(name)){zip.closeEntry();continue;}
                    if(++count>MAX_FILES)throw new java.io.IOException("Слишком много файлов");
                    File target=new File(temp,name);
                    if(target.exists())throw new java.io.IOException("Повтор файла "+name);
                    long written=0;
                    try(OutputStream out=new FileOutputStream(target)){
                        int n;
                        while((n=zip.read(buffer))!=-1){
                            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                            if(n==0)continue;
                            total+=n;written+=n;
                            if(total>MAX_BYTES)throw new java.io.IOException("Архив слишком большой");
                            out.write(buffer,0,n);
                        }
                    }
                    if(written==0){target.delete();throw new java.io.IOException("Пустой файл "+name);}
                    if(name.matches(PART))audioCount++;
                    if(audioCount>0&&audioCount%4==0)
                        progress.report("Импортировано аудиофрагментов: "+audioCount);
                    zip.closeEntry();
                }
            }
        }catch(Exception e){clean(temp);throw e;}
        if(audioCount==0){clean(temp);throw new java.io.IOException("В ZIP нет файлов part_0001.m4a и подобных");}
        if(!temp.renameTo(dest)){clean(temp);throw new java.io.IOException("Не удалось сохранить импорт");}
        setSelected(c,dest);
        return dest;
    }
    static void importText(Context c,Uri source,File session)throws Exception{
        if(session==null||find(c,session.getName())==null)
            throw new java.io.IOException("Сначала выбери сессию");
        File temp=new File(session,"transcript_imported_ru.part");
        File dest=new File(session,"transcript_imported_ru.txt");
        long total=0;
        try(InputStream in=c.getContentResolver().openInputStream(source)){
            if(in==null)throw new java.io.IOException("Не открыть файл");
            try(OutputStream out=new FileOutputStream(temp)){
                byte[] b=new byte[32768];int n;
                while((n=in.read(b))!=-1){
                    if(n==0)continue;
                    total+=n;
                    if(total>20L*1024*1024)throw new java.io.IOException("TXT больше 20 МБ");
                    out.write(b,0,n);
                }
            }
            if(total==0)throw new java.io.IOException("Пустой TXT");
            // Preserve any previously imported text until the new copy is complete.
            if(dest.exists()&&!dest.delete())throw new java.io.IOException("Не заменить прежний TXT");
            if(!temp.renameTo(dest))throw new java.io.IOException("Не сохранить TXT");
        }finally{temp.delete();}
    }
    private static void clean(File f){
        File[] children=f.listFiles();
        if(children!=null)for(File x:children)clean(x);
        f.delete();
    }
}
