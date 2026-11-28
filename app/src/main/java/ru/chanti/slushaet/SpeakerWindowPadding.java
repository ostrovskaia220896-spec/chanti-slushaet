package ru.chanti.slushaet;

/** Input geometry of the pinned pyannote 3.0 model, shift ratio 0.1.
 * Temporary zero padding only; never changes the source audio or its duration. */
final class SpeakerWindowPadding {
    static final int WINDOW=160000, HOP=16000;
    private SpeakerWindowPadding(){}
    static int paddedLength(int n){
        if(n<=0)throw new IllegalArgumentException("Empty audio");
        if(n<=WINDOW)return WINDOW;
        long extra=(long)n-WINDOW;
        long result=WINDOW+((extra+HOP-1)/HOP)*HOP;
        if(result>Integer.MAX_VALUE)throw new IllegalArgumentException("Audio too long");
        return (int)result;
    }
    static float[] pad(float[] samples){
        int length=paddedLength(samples.length);
        return length==samples.length?samples:java.util.Arrays.copyOf(samples,length);
    }
}
