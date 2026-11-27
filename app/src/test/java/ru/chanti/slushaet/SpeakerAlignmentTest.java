package ru.chanti.slushaet;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;

public class SpeakerAlignmentTest {
    private SpeakerAlignment.Turn t(long a,long b,String id){return new SpeakerAlignment.Turn(a,b,id);}
    @Test public void oneSpeaker(){assertEquals("s1",SpeakerAlignment.assign(1000,3000,
        Arrays.asList(t(0,3500,"s1"),t(4000,5000,"s2"))));}
    @Test public void overlappingVoicesAreNotGuessed(){assertEquals(SpeakerAlignment.MULTIPLE,
        SpeakerAlignment.assign(0,2000,Arrays.asList(t(0,2000,"s1"),t(500,1800,"s2"))));}
    @Test public void sequentialVoicesWithinOneTextAreAmbiguous(){assertEquals(SpeakerAlignment.MULTIPLE,
        SpeakerAlignment.assign(0,2000,Arrays.asList(t(0,1000,"s1"),t(1000,2000,"s2"))));}
    @Test public void littleEvidenceIsUnknown(){assertEquals(SpeakerAlignment.UNKNOWN,
        SpeakerAlignment.assign(0,3000,Arrays.asList(t(0,500,"s1"))));}
    @Test public void repeatedIntervalsDoNotInflateCoverage(){assertEquals(SpeakerAlignment.UNKNOWN,
        SpeakerAlignment.assign(0,2000,Arrays.asList(t(0,1000,"s1"),t(0,1000,"s1"))));}
    @Test public void missingTimesStayUnknown(){assertEquals(SpeakerAlignment.UNKNOWN,
        SpeakerAlignment.assign(0,0,Collections.emptyList()));}
    @Test public void noFixedSpeakerCount(){assertEquals("s17",SpeakerAlignment.assign(100,1000,
        Arrays.asList(t(0,1000,"s17"))));}
}
