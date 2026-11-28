package ru.chanti.slushaet;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeakerWindowPaddingTest {
    @Test public void exactWindowUnchanged(){
        assertEquals(160000,SpeakerWindowPadding.paddedLength(160000));
        assertEquals(4800000,SpeakerWindowPadding.paddedLength(4800000));
    }
    @Test public void partialWindowAligned(){
        assertEquals(176000,SpeakerWindowPadding.paddedLength(160001));
        assertEquals(4800000,SpeakerWindowPadding.paddedLength(4799999));
        assertEquals(160000,SpeakerWindowPadding.paddedLength(1));
    }
    @Test public void preservesOriginalAndAppendsOnlyZeros(){
        float[] original={0.25f,-0.5f};
        float[] padded=SpeakerWindowPadding.pad(original);
        assertEquals(2,original.length);
        assertEquals(160000,padded.length);
        assertEquals(0.25f,padded[0],0f);
        assertEquals(-0.5f,padded[1],0f);
        assertEquals(0f,padded[2],0f);
        assertEquals(0f,padded[padded.length-1],0f);
    }
    @Test public void alignedInputDoesNotAllocate(){
        float[] original=new float[160000];
        assertSame(original,SpeakerWindowPadding.pad(original));
    }
}
