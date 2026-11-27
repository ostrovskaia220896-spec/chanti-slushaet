package ru.chanti.slushaet;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds a separate, speaker-labelled transcript without modifying Whisper's source text. */
final class MagirDiarizationFiles {
    private MagirDiarizationFiles(){}

    static File sidecar(File dir,String audioName){
        return new File(dir,MagirFileRules.speakerTurnsForAudio(audioName));
    }

    static void writeSidecar(File target,long offsetMs,
            List<DetectedSpeakerTurn> turns)throws Exception{
        JSONObject root=new JSONObject();
        root.put("schema",1);
        root.put("offsetMs",offsetMs);
        JSONArray list=new JSONArray();
        for(DetectedSpeakerTurn turn:turns){
            JSONObject item=new JSONObject();
            item.put("startMs",turn.getStartMs());
            item.put("endMs",turn.getEndMs());
            item.put("speakerId",turn.getSpeakerId());
            item.put("confidence",turn.getConfidence());
            list.put(item);
        }
        root.put("turns",list);
        try(FileOutputStream out=new FileOutputStream(target,false)){
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    static void buildTranscript(File dir,File[] parts,File[] speakerFiles,
            File destination)throws Exception{
        if(parts.length!=speakerFiles.length)throw new IllegalArgumentException("Part count mismatch");
        File temporary=new File(destination.getParentFile(),destination.getName()+".tmp");
        try(BufferedWriter out=new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temporary,false),StandardCharsets.UTF_8))){
            out.write("МИРЫ МАГИРА — черновая расшифровка с автоматическим разделением голосов\n");
            out.write("Номера голосов предварительные. Имена участников автоматически не определяются.\n");
            out.write("Неопределённые и перекрывающиеся реплики отмечены отдельно. Исходный текст Whisper не изменён.\n\n");
            for(int p=0;p<parts.length;p++){
                File whisperFile=new File(dir,MagirFileRules.segmentsForAudio(parts[p].getName()));
                if(!whisperFile.isFile()||whisperFile.length()==0)
                    throw new IllegalStateException("Нет временных отметок Whisper для "+parts[p].getName());
                if(!speakerFiles[p].isFile()||speakerFiles[p].length()==0)
                    throw new IllegalStateException("Нет разметки голосов для "+parts[p].getName());
                JSONObject whisper=new JSONObject(read(whisperFile));
                JSONObject speakerData=new JSONObject(read(speakerFiles[p]));
                long whisperOffset=whisper.optLong("offsetMs",0L);
                long speakerOffset=speakerData.optLong("offsetMs",whisperOffset);
                JSONArray speakerSegments=speakerData.optJSONArray("turns");
                List<SpeakerAlignment.Turn> turns=new ArrayList<>();
                if(speakerSegments!=null){
                    for(int i=0;i<speakerSegments.length();i++){
                        JSONObject item=speakerSegments.optJSONObject(i);
                        if(item==null)continue;
                        String id=item.optString("speakerId","unknown");
                        if("unknown".equals(id)||"multiple".equals(id)||id.isEmpty())continue;
                        long start=item.optLong("startMs",-1L);
                        long end=item.optLong("endMs",-1L);
                        if(start>=0&&end>start)
                            turns.add(new SpeakerAlignment.Turn(
                                speakerOffset+start,speakerOffset+end,id));
                    }
                }
                JSONArray textSegments=whisper.optJSONArray("segments");
                if(textSegments!=null){
                    for(int i=0;i<textSegments.length();i++){
                        JSONObject item=textSegments.optJSONObject(i);
                        if(item==null)continue;
                        String text=item.optString("text","").trim();
                        if(text.isEmpty())continue;
                        long start=item.optLong("startMs",-1L);
                        long end=item.optLong("endMs",-1L);
                        String speaker="unknown";
                        if(start>=0&&end>start)
                            speaker=SpeakerAlignment.assign(
                                whisperOffset+start,whisperOffset+end,turns);
                        String time=start>=0?clock(whisperOffset+start):"время не определено";
                        out.write("["+time+"] ["+label(speaker)+"] "+text);
                        out.newLine();
                    }
                }
                JSONArray untimed=whisper.optJSONArray("untimedText");
                if(untimed!=null){
                    for(int i=0;i<untimed.length();i++){
                        String text=untimed.optString(i,"").trim();
                        if(!text.isEmpty()){
                            out.write("[время не определено] [Голос не определён] "+text);
                            out.newLine();
                        }
                    }
                }
                out.newLine();
            }
        }catch(Exception error){
            temporary.delete();
            throw error;
        }
        MagirSessions.replaceFile(temporary,destination);
    }

    private static String read(File file)throws Exception{
        try(FileInputStream in=new FileInputStream(file)){
            byte[] buffer=new byte[32768];
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            return out.toString("UTF-8");
        }
    }
    private static String clock(long millis){
        long seconds=Math.max(0,millis/1000L);
        return String.format(Locale.US,"%02d:%02d:%02d",
            seconds/3600,(seconds/60)%60,seconds%60);
    }
    private static String label(String id){
        if("multiple".equals(id))return "Несколько голосов";
        if("unknown".equals(id)||id==null||id.isEmpty())return "Голос не определён";
        if(id.startsWith("speaker_")){
            try{return "Голос "+Integer.parseInt(id.substring("speaker_".length()));}
            catch(NumberFormatException ignored){}
        }
        return "Голос не определён";
    }
}
