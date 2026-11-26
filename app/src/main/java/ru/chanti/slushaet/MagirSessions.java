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
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Persistent, independent recording sessions and safe import/export primitives. */
final class MagirSessions {
    static final String PREF="magir_sessions_v10";
    static final String PART="part_\\d{4}\\.m4a";
    static final String PENDING_PART="part_\\d{4}\\.m4a\\.pending";
    private static final int MAX_FILES=1500;
    private static final long MAX_BYTES=4L*1024*1024*1024;
    private static volatile boolean recovered=false;
    private MagirSessions(){}

    private static SharedPreferences pref(Context c){
        return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    static File root(Context c){
        File directory=c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        return directory==null?null:new File(directory,"Magir");
    }
    private static File createDirectory(Context c,String prefix)throws IOException{
        File base=root(c);
        if(base==null||(!base.exists()&&!base.mkdirs()))
            throw new IOException("Не удалось открыть хранилище записей");
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
        for(int suffix=0;suffix<100;suffix++){
            String name=prefix+stamp+(suffix==0?"":"_"+suffix);
            File dir=new File(base,name);
            if(dir.mkdir()){
                writeMeta(dir,"created_at",Long.toString(System.currentTimeMillis()));
                return dir;
            }
        }
        throw new IOException("Не удалось создать уникальную папку записи");
    }
    static File createRecordingSession(Context c)throws IOException{
        return createDirectory(c,"Magir_");
    }
    private static File createImportedSession(Context c)throws IOException{
        File base=root(c);
        if(base==null||(!base.exists()&&!base.mkdirs()))
            throw new IOException("Не удалось открыть хранилище записей");
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
        for(int suffix=0;suffix<100;suffix++){
            String name="Magir_import_"+stamp+(suffix==0?"":"_"+suffix)+".partial";
            File dir=new File(base,name);
            if(dir.mkdir()){
                writeMeta(dir,"created_at",Long.toString(System.currentTimeMillis()));
                return dir;
            }
        }
        throw new IOException("Не удалось создать временную папку импорта");
    }
    private static void writeMeta(File dir,String key,String value){
        File file=new File(dir,"session.properties");
        Properties p=new Properties();
        if(file.isFile())try(InputStream in=new FileInputStream(file)){p.load(in);}catch(Exception ignored){}
        p.setProperty(key,value);
        File temp=new File(dir,"session.properties.tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){p.store(out,"Chanti Magir session");out.getFD().sync();}
        catch(Exception e){temp.delete();return;}
        try{replaceFile(temp,file);}catch(Exception ignored){temp.delete();}
    }
    static void markStopped(File dir,long when){
        if(dir!=null)writeMeta(dir,"stopped_at",Long.toString(when));
    }
    static File[] all(Context c){
        File base=root(c);
        if(base==null)return new File[0];
        File[] result=base.listFiles(f->f.isDirectory()&&f.getName().startsWith("Magir_")
            && !f.getName().endsWith(".partial"));
        if(result==null)return new File[0];
        Arrays.sort(result,(a,b)->Long.compare(createdAt(b),createdAt(a)));
        return result;
    }
    static File find(Context c,String name){
        if(name==null||!name.matches("Magir_[a-zA-Z0-9_-]{6,80}"))return null;
        File base=root(c);
        if(base==null)return null;
        File f=new File(base,name);
        try{
            if(!f.getCanonicalFile().getParentFile().equals(base.getCanonicalFile()))return null;
        }catch(Exception e){return null;}
        return f.isDirectory()?f:null;
    }
    static long createdAt(File dir){
        if(dir==null)return 0;
        File meta=new File(dir,"session.properties");
        if(meta.isFile()){
            Properties p=new Properties();
            try(InputStream in=new FileInputStream(meta)){
                p.load(in);
                return Long.parseLong(p.getProperty("created_at",Long.toString(dir.lastModified())));
            }catch(Exception ignored){}
        }
        return dir.lastModified();
    }
    static synchronized void recover(Context c){
        if(recovered)return;
        File base=root(c);
        if(base==null||!base.exists())return;
        recovered=true;
        File[] dirs=base.listFiles(File::isDirectory);
        if(dirs==null)return;
        for(File dir:dirs){
            if(dir.getName().endsWith(".partial")){clean(dir);continue;}
            File[] files=dir.listFiles();
            if(files==null)continue;
            for(File file:files){
                String name=file.getName();
                if(name.endsWith(".bak")){
                    File original=new File(dir,name.substring(0,name.length()-4));
                    if(original.exists())file.delete();
                    else file.renameTo(original);
                }else if(name.equals("transcript_imported_ru.part")){
                    file.delete();
                }
            }
        }
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
    static int pendingAudioCount(File dir){
        if(dir==null)return 0;
        File[] files=dir.listFiles((d,name)->name.matches(PENDING_PART));
        return files==null?0:files.length;
    }
    static int completedTextParts(File dir){
        if(dir==null)return 0;
        File[] files=dir.listFiles((d,name)->name.matches("part_\\d{4}\\.whisper\\.txt")&&new File(d,name).length()>0);
        return files==null?0:files.length;
    }
    static boolean fullyTranscribed(File dir){
        File[] files=audio(dir);
        if(files.length==0)return false;
        for(File part:files){
            File text=new File(dir,part.getName().replace(".m4a",".whisper.txt"));
            if(!text.isFile()||text.length()==0)return false;
        }
        return true;
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
        int pending=pendingAudioCount(dir);
        File txt=transcript(dir);
        String name=dir.getName();
        String date=new SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault())
            .format(new Date(createdAt(dir)));
        return date+" · "+parts+" аудио · "+done+"/"+parts+" расшифровано"+
            (pending>0?" · "+pending+" незавершённых":"")+
            (txt!=null?" · TXT":"")+(name.contains("_import_")?" · импорт":"");
    }

    interface Progress { void report(String message); }
    private static boolean accepted(String name){
        return MagirFileRules.isSafeImportEntry(name);
    }
    static File importZip(Context c,Uri source,Progress progress)throws Exception{
        File temp=createImportedSession(c);
        int count=0,audioCount=0;
        long total=0;
        try(InputStream input=c.getContentResolver().openInputStream(source)){
            if(input==null)throw new IOException("Не удалось прочитать ZIP");
            try(ZipInputStream zip=new ZipInputStream(input)){
                ZipEntry entry;
                byte[] buffer=new byte[65536];
                while((entry=zip.getNextEntry())!=null){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(entry.isDirectory()){zip.closeEntry();continue;}
                    String name=entry.getName();
                    if(!accepted(name)){zip.closeEntry();continue;}
                    if(++count>MAX_FILES)throw new IOException("Слишком много файлов");
                    File target=new File(temp,name);
                    if(target.exists())throw new IOException("Повтор файла "+name);
                    long written=0;
                    try(OutputStream out=new FileOutputStream(target)){
                        int n;
                        while((n=zip.read(buffer))!=-1){
                            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                            if(n==0)continue;
                            total+=n;written+=n;
                            if(total>MAX_BYTES)throw new IOException("Архив слишком большой");
                            out.write(buffer,0,n);
                        }
                    }
                    if(written==0){target.delete();throw new IOException("Пустой файл "+name);}
                    if(MagirFileRules.isCompleteAudio(name)||MagirFileRules.isPendingAudio(name))audioCount++;
                    if(progress!=null&&audioCount>0&&audioCount%4==0)
                        progress.report("Импортировано аудиофрагментов: "+audioCount);
                    zip.closeEntry();
                }
            }
            if(audioCount==0)throw new IOException("В ZIP нет аудиофрагментов Magir");
            File base=root(c);
            String tempName=temp.getName();
            File dest=new File(base,tempName.substring(0,tempName.length()-".partial".length()));
            if(!temp.renameTo(dest))throw new IOException("Не удалось сохранить импорт");
            setSelected(c,dest);
            return dest;
        }catch(Exception e){clean(temp);throw e;}
    }
    static void importText(Context c,Uri source,File session)throws Exception{
        if(session==null||find(c,session.getName())==null)throw new IOException("Сначала выбери сессию");
        File temp=new File(session,"transcript_imported_ru.part");
        File dest=new File(session,"transcript_imported_ru.txt");
        long total=0;
        try(InputStream in=c.getContentResolver().openInputStream(source)){
            if(in==null)throw new IOException("Не открыть файл");
            try(OutputStream out=new FileOutputStream(temp)){
                byte[] b=new byte[32768];int n;
                while((n=in.read(b))!=-1){
                    if(n==0)continue;
                    total+=n;
                    if(total>20L*1024*1024)throw new IOException("TXT больше 20 МБ");
                    out.write(b,0,n);
                }
            }
            if(total==0)throw new IOException("Пустой TXT");
            replaceFile(temp,dest);
        }finally{temp.delete();}
    }
    /** Replace a file without deleting the last known-good copy first. */
    static void replaceFile(File temp,File destination)throws IOException{
        File backup=new File(destination.getParentFile(),destination.getName()+".bak");
        if(backup.exists()&&!backup.delete())throw new IOException("Не удалить резервный файл");
        boolean hadOld=destination.exists();
        if(hadOld&&!destination.renameTo(backup))throw new IOException("Не сохранить предыдущую версию");
        if(!temp.renameTo(destination)){
            if(hadOld)backup.renameTo(destination);
            throw new IOException("Не удалось сохранить новый файл");
        }
        if(backup.exists())backup.delete();
    }
    private static void clean(File f){
        File[] children=f.listFiles();
        if(children!=null)for(File x:children)clean(x);
        f.delete();
    }
}
