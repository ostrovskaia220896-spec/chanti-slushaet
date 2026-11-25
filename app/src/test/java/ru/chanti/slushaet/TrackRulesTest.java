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
    @Test public void shortListenDoesNotMeanDislike() {
        assertTrue(TrackRules.switchNote(2000, true).contains("не обязательно дизлайк"));
        assertEquals("", TrackRules.switchNote(31000, true));
        assertEquals("", TrackRules.switchNote(2000, false));
    }
}
