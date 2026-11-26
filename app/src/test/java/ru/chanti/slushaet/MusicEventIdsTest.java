package ru.chanti.slushaet;

import static org.junit.Assert.*;
import org.junit.Test;

public final class MusicEventIdsTest {
    @Test public void eventIdsAreUnique() {
        String first = MusicEventIds.newEventId();
        String second = MusicEventIds.newEventId();
        assertNotEquals(first, second);
    }

    @Test public void requestIdMatchesBridgeFormat() {
        String requestId = MusicEventIds.requestId("music-event-001");
        assertTrue(requestId.matches("q_[a-f0-9]{64}"));
    }

    @Test public void requestIdIsStableAcrossRetries() {
        String first = MusicEventIds.requestId("same-event-id");
        String second = MusicEventIds.requestId("same-event-id");
        assertEquals(first, second);
    }

    @Test public void differentEventsGetDifferentRequestIds() {
        assertNotEquals(
            MusicEventIds.requestId("event-one"),
            MusicEventIds.requestId("event-two"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void requestIdRejectsEmptyEventId() {
        MusicEventIds.requestId(" ");
    }
}
