package ru.chanti.slushaet;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class MagirQueueRulesTest {
    @Test public void enqueueIsIdempotentAndPreservesOrder(){
        assertEquals(Arrays.asList("Magir_20261126_110000_001","Magir_20261126_110001_002"),
            MagirQueueRules.enqueue(
                MagirQueueRules.enqueue(Collections.singletonList("Magir_20261126_110000_001"),
                    "Magir_20261126_110001_002"),
                "Magir_20261126_110000_001"));
    }
    @Test public void queueRejectsPathLikeOrMalformedSessionIds(){
        assertFalse(MagirQueueRules.validSessionName("../Magir_20261126_110000"));
        assertFalse(MagirQueueRules.validSessionName("Magir_"));
        assertEquals(Collections.emptyList(),
            MagirQueueRules.enqueue(Collections.emptyList(),"../Magir_20261126_110000"));
    }
    @Test public void dequeuesOnlyTheHead(){
        assertEquals("Magir_20261126_110000_001",
            MagirQueueRules.first(Arrays.asList("Magir_20261126_110000_001","Magir_20261126_110001_002")));
        assertEquals(Collections.singletonList("Magir_20261126_110001_002"),
            MagirQueueRules.withoutFirst(Arrays.asList("Magir_20261126_110000_001","Magir_20261126_110001_002")));
    }
}
