package ru.chanti.slushaet;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** Decode saved M4A/AAC through Android codecs into 16k mono PCM for offline Vosk. */
final class MagirSpeechDecoder {
    interface Progress {void status(String description);}
    static double duration(File file) {
        MediaMetadataRetriever m=new MediaMetadataRetriever();
        try{
            m.setDataSource(file.getAbsolutePath());
            String d=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d==null?0:Long.parseLong(d)/1000.0;
        }catch(Exception e){return 0;}
        finally{try{m.release();}catch(Exception ignored){}}
    }
    static void transcribe(File source,Model model,File transcript,double offset,Progress progress)
            throws Exception {
        MediaExtractor ex=new MediaExtractor();
        MediaCodec decoder=null;
        try{
            ex.setDataSource(source.getAbsolutePath());
            int track=-1;MediaFormat format=null;
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat f=ex.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){track=i;format=f;break;}
            }
            if(track<0||format==null)throw new IllegalStateException("В файле нет аудио");
            ex.selectTrack(track);
            String mime=format.getString(MediaFormat.KEY_MIME);
            decoder=MediaCodec.createDecoderByType(mime);
            decoder.configure(format,null,null,0);
            decoder.start();

            int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            boolean inputEnd=false,outputEnd=false;
            int idle=0;
            try(Recognizer recognizer=new Recognizer(model,16000.0f);
                FileWriter out=new FileWriter(transcript,true)){
                recognizer.setWords(true);
                Resampler resample=new Resampler(rate,channels,recognizer,out,offset);
                while(!outputEnd){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(!inputEnd){
                        int inIndex=decoder.dequeueInputBuffer(15000);
                        if(inIndex>=0){
                            ByteBuffer in=decoder.getInputBuffer(inIndex);
                            if(in==null)throw new IllegalStateException("Нет буфера декодера");
                            in.clear();
                            int size=ex.readSampleData(in,0);
                            if(size<0){
                                decoder.queueInputBuffer(inIndex,0,0,0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEnd=true;
                            }else{
                                long timestamp=ex.getSampleTime();
                                decoder.queueInputBuffer(inIndex,0,size,timestamp,0);
                                ex.advance();
                            }
                        }
                    }
                    int outIndex=decoder.dequeueOutputBuffer(info,15000);
                    if(outIndex==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                        MediaFormat outFormat=decoder.getOutputFormat();
                        rate=outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        channels=outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        int pcm=outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ?outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                        if(pcm!=AudioFormat.ENCODING_PCM_16BIT)
                            throw new IllegalStateException("Неподдерживаемый PCM-формат "+pcm);
                        resample.configure(rate,channels);
                    }else if(outIndex>=0){
                        idle=0;
                        ByteBuffer buffer=decoder.getOutputBuffer(outIndex);
                        if(buffer!=null&&info.size>0){
                            buffer.position(info.offset);
                            buffer.limit(info.offset+info.size);
                            resample.consume(buffer.order(ByteOrder.LITTLE_ENDIAN));
                        }
                        outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                        decoder.releaseOutputBuffer(outIndex,false);
                    }else if(++idle>150)
                        throw new IllegalStateException("Декодер аудио не отвечает");
                }
                resample.flush();
                append(recognizer.getFinalResult(),out,offset);
                out.flush();
            }
        }finally{
            if(decoder!=null){
                try{decoder.stop();}catch(Exception ignored){}
                try{decoder.release();}catch(Exception ignored){}
            }
            ex.release();
        }
    }

    private static void append(String json,FileWriter out,double base)throws Exception{
        JSONObject result=new JSONObject(json);
        String text=result.optString("text","").trim();
        if(text.isEmpty())return;
        JSONArray words=result.optJSONArray("result");
        double when=base;
        if(words!=null&&words.length()>0){
            JSONObject first=words.optJSONObject(0);
            if(first!=null)when+=Math.max(0,first.optDouble("start",0));
        }
        long seconds=(long)when;
        out.write(String.format(Locale.US,"[%02d:%02d:%02d] %s\n",
            seconds/3600,(seconds/60)%60,seconds%60,text));
        out.flush();
    }

    private static final class Resampler {
        private int rate,channels;
        private final Recognizer recognizer;
        private final FileWriter file;
        private final double base;
        private final byte[] pending=new byte[8192];
        private int used=0;
        private long index=0;
        private double next=0;
        private short previous=0;
        Resampler(int rate,int channels,Recognizer recognizer,FileWriter file,double base){
            this.recognizer=recognizer;this.file=file;this.base=base;
            configure(rate,channels);
        }
        void configure(int sampleRate,int numberOfChannels) {
            rate=sampleRate;channels=numberOfChannels;
            if(rate<8000||rate>192000||channels<1||channels>8)
                throw new IllegalStateException("Неподдерживаемая частота/каналы");
        }
        void consume(ByteBuffer pcm)throws Exception{
            int frameBytes=channels*2;
            while(pcm.remaining()>=frameBytes){
                int sum=0;
                for(int c=0;c<channels;c++)sum+=pcm.getShort();
                short sample=(short)(sum/channels);
                double step=rate/16000.0;
                while(next<=index){
                    double alpha=index==0?1.0:Math.max(0,Math.min(1,next-(index-1)));
                    short mono=index==0?sample:(short)Math.round(previous+(sample-previous)*alpha);
                    pending[used++]=(byte)(mono&255);
                    pending[used++]=(byte)((mono>>8)&255);
                    if(used==pending.length)flush();
                    next+=step;
                }
                previous=sample;index++;
            }
        }
        void flush()throws Exception{
            if(used==0)return;
            if(recognizer.acceptWaveForm(pending,used))
                append(recognizer.getResult(),file,base);
            used=0;
        }
    }
    private MagirSpeechDecoder(){}
}
