package ru.chanti.slushaet;

import static org.junit.Assert.*;
import org.junit.Test;

public final class MagirFileRulesTest {
    @Test public void recognizesOnlyFinalizedAudio(){
        assertTrue(MagirFileRules.isCompleteAudio("part_0001.m4a"));
        assertFalse(MagirFileRules.isCompleteAudio("part_0001.m4a.pending"));
        assertFalse(MagirFileRules.isCompleteAudio("../part_0001.m4a"));
    }
    @Test public void keepsInterruptedAudioAsPendingNotTranscribable(){
        assertTrue(MagirFileRules.isPendingAudio("part_0024.m4a.pending"));
        assertFalse(MagirFileRules.isPendingAudio("part_0024.m4a"));
        assertFalse(MagirFileRules.isPendingAudio("folder/part_0024.m4a.pending"));
    }
    @Test public void onlyAllowsKnownFlatZipEntries(){
        assertTrue(MagirFileRules.isSafeImportEntry("part_0001.m4a"));
        assertTrue(MagirFileRules.isSafeImportEntry("part_0001.m4a.pending"));
        assertTrue(MagirFileRules.isSafeImportEntry("part_0001.whisper.txt"));
        assertTrue(MagirFileRules.isSafeImportEntry("transcript_whisper_ru.txt"));
        assertFalse(MagirFileRules.isSafeImportEntry("../part_0001.m4a"));
        assertFalse(MagirFileRules.isSafeImportEntry("nested/part_0001.m4a"));
        assertFalse(MagirFileRules.isSafeImportEntry("classes.dex"));
    }
    @Test public void acceptsOnlyFinalizedTimedSidecars(){
        assertTrue(MagirFileRules.isSafeImportEntry("part_0001.whisper.json"));
        assertFalse(MagirFileRules.isSafeImportEntry("part_0001.whisper.json.tmp"));
        assertFalse(MagirFileRules.isSafeImportEntry("../part_0001.whisper.json"));
        assertEquals("part_0007.whisper.json",MagirFileRules.segmentsForAudio("part_0007.m4a"));
    }
    @Test public void mapsAudioToItsOwnCheckpoint(){
        assertEquals("part_0007.whisper.txt",MagirFileRules.transcriptForAudio("part_0007.m4a"));
    }
    @Test(expected=IllegalArgumentException.class)
    public void refusesPendingAudioCheckpoint(){
        MagirFileRules.transcriptForAudio("part_0007.m4a.pending");
    }
}
