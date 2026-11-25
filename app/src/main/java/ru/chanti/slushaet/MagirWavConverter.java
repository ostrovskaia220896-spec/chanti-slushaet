package ru.chanti.slushaet;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts recorded AAC/M4A into 16000 Hz mono 16-bit WAV required by whisper-android. */
final class MagirWavConverter {
    private MagirWavConverter(){}
    static File convert(File input,File dir)throws Exception{
        File wav=new File(dir,input.getName().replace(".m4a",".whisper.wav"));
        if(wav.exists())wav.delete();
        MediaExtractor ex=new MediaExtractor();
        MediaCodec decoder=null;
        try{
            ex.setDataSource(input.getAbsolutePath());
            MediaFormat fmt=null;int track=-1;
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat f=ex.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){fmt=f;track=i;break;}
            }
            if(track<0)throw new IllegalStateException("В фрагменте нет звуковой дорожки");
            ex.selectTrack(track);
            decoder=MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            decoder.configure(fmt,null,null,0);decoder.start();
            int rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int[] frames=new int[]{0};
            try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(wav),65536)){
                out.write(new byte[44]);
                Downsampler sampler=new Downsampler(out,rate,channels,frames);
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                boolean inputFinished=false,outputFinished=false;
                int idle=0;
                while(!outputFinished){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(!inputFinished){
                        int idx=decoder.dequeueInputBuffer(15000);
                        if(idx>=0){
                            ByteBuffer buf=decoder.getInputBuffer(idx);
                            if(buf==null)throw new IllegalStateException("Нет входного буфера");
                            buf.clear();
                            int size=ex.readSampleData(buf,0);
                            if(size<0){
                                decoder.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputFinished=true;
                            }else{
                                decoder.queueInputBuffer(idx,0,size,ex.getSampleTime(),0);
                                ex.advance();
                            }
                        }
                    }
                    int idx=decoder.dequeueOutputBuffer(info,15000);
                    if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                        MediaFormat output=decoder.getOutputFormat();
                        rate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        channels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        int pcm=output.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ?output.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                        if(pcm!=AudioFormat.ENCODING_PCM_16BIT)
                            throw new IllegalStateException("Декодер выдал неподдерживаемый PCM");
                        sampler.configure(rate,channels);
                    }else if(idx>=0){
                        idle=0;
                        ByteBuffer pcm=decoder.getOutputBuffer(idx);
                        if(pcm!=null&&info.size>0){
                            pcm.position(info.offset);
                            pcm.limit(info.offset+info.size);
                            sampler.feed(pcm.order(ByteOrder.LITTLE_ENDIAN));
                        }
                        outputFinished=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                        decoder.releaseOutputBuffer(idx,false);
                    }else if(++idle>150)throw new IllegalStateException("Декодер не отвечает");
                }
                out.flush();
            }
            if(frames[0]==0)throw new IllegalStateException("Аудиофрагмент пуст");
            long dataLength=frames[0]*2L;
            if(dataLength>Integer.MAX_VALUE-36)
                throw new IllegalStateException("Слишком длинный фрагмент WAV");
            try(RandomAccessFile raf=new RandomAccessFile(wav,"rw")){
                raf.seek(0);
                raf.writeBytes("RIFF");little32(raf,(int)(36+dataLength));
                raf.writeBytes("WAVEfmt ");little32(raf,16);
                little16(raf,1);little16(raf,1);little32(raf,16000);
                little32(raf,32000);little16(raf,2);little16(raf,16);
                raf.writeBytes("data");little32(raf,(int)dataLength);
            }
            return wav;
        }catch(Exception e){wav.delete();throw e;}
        finally{
            if(decoder!=null){
                try{decoder.stop();}catch(Exception ignored){}
                try{decoder.release();}catch(Exception ignored){}
            }
            ex.release();
        }
    }
    private static void little16(RandomAccessFile f,int v)throws Exception{
        f.write(v&255);f.write((v>>8)&255);
    }
    private static void little32(RandomAccessFile f,int v)throws Exception{
        f.write(v&255);f.write((v>>8)&255);
        f.write((v>>16)&255);f.write((v>>24)&255);
    }
    private static final class Downsampler{
        private final BufferedOutputStream out;
        private final int[] frames;
        private int rate,channels;
        private long inIndex=0;
        private double nextOutput=0;
        private short previous=0;
        Downsampler(BufferedOutputStream out,int rate,int ch,int[] frames){
            this.out=out;this.frames=frames;configure(rate,ch);
        }
        void configure(int rate,int channels){
            if(rate<8000||rate>192000||channels<1||channels>8)
                throw new IllegalArgumentException("Неподдерживаемый звук");
            this.rate=rate;this.channels=channels;
        }
        void feed(ByteBuffer pcm)throws Exception{
            int frameSize=2*channels;
            while(pcm.remaining()>=frameSize){
                int mix=0;
                for(int c=0;c<channels;c++)mix+=pcm.getShort();
                short current=(short)(mix/channels);
                double step=rate/16000.0;
                while(nextOutput<=inIndex){
                    double factor=inIndex==0?1.0:Math.max(0,Math.min(1,nextOutput-(inIndex-1)));
                    int value=inIndex==0?current:
                        (int)Math.round(previous+(current-previous)*factor);
                    out.write(value&255);out.write((value>>8)&255);
                    frames[0]++;
                    nextOutput+=step;
                }
                previous=current;inIndex++;
            }
        }
    }
}
