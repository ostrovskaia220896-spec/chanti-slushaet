package ru.chanti.slushaet;

import static org.junit.Assert.*;
import org.junit.Test;

public final class TrackRulesTest {
    @Test public void detectsChangeOfArtistEvenWhenTitleSame() {
        assertTrue(TrackRules.changed("Песня", "Группа 1", "Песня", "Группа 2"));
    }
    @Test public void detectsChangeOfTitle() {
        assertTrue(TrackRules.changed("Песня 1", "Группа", "Песня 2", "Группа"));
    }
    @Test public void ignoresWhitespaceOnlyChanges() {
        assertFalse(TrackRules.changed("Песня ", " Группа", "Песня", "Группа "));
    }
    @Test public void capturesLikeUnlikeLikeSequence() {
        assertEquals("лайк уже стоит (по данным плеера)", TrackRules.ratingNote("", "liked"));
        assertEquals("лайк снят (по данным плеера)", TrackRules.ratingNote("liked", "unliked"));
        assertEquals("лайк появился (по данным плеера)", TrackRules.ratingNote("unliked", "liked"));
    }
    @Test public void showsNotLikedWithoutClaimingRemoval() {
        assertEquals("сейчас без лайка (по данным плеера)", TrackRules.ratingNote("", "unliked"));
    }
    @Test public void shortListenDoesNotMeanDislike() {
        assertTrue(TrackRules.switchNote(2000, true).contains("не обязательно дизлайк"));
        assertEquals("", TrackRules.switchNote(31000, true));
        assertEquals("", TrackRules.switchNote(2000, false));
    }
    @Test public void freshMediaSampleAllowsTimerToAdvance() {
        assertTrue(TrackRules.observationFresh(5000,9000));
        assertEquals(8000L,TrackRules.observedPlayingMs(1000,5000,9000));
    }
    @Test public void staleSampleDoesNotAttributeTwentyMinutesToOldSong() {
        assertFalse(TrackRules.observationFresh(5000,1445000));
        assertEquals(4000L,TrackRules.observedPlayingMs(1000,5000,1445000));
    }
    @Test public void missingOrInvalidSampleDoesNotInventDuration() {
        assertFalse(TrackRules.observationFresh(0,5000));
        assertFalse(TrackRules.observationFresh(6000,5000));
        assertEquals(0L,TrackRules.observedPlayingMs(1000,0,5000));
        assertEquals(0L,TrackRules.observedPlayingMs(6000,7000,5000));
    }
}
