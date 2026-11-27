package ru.chanti.slushaet

import org.junit.Assert.*
import org.junit.Test

class SpeakerIdentityMatcherTest {
    @Test fun repeatedVoiceReusesSessionId() {
        val matcher = SpeakerIdentityMatcher(threshold = 0.5f)
        val first = matcher.assign(listOf(floatArrayOf(1f, 0f, 0f)))
        val repeated = matcher.assign(listOf(floatArrayOf(0.99f, 0.01f, 0f)))
        assertEquals("speaker_01", first.speakerId)
        assertEquals(first.speakerId, repeated.speakerId)
        assertEquals(SpeakerIdentityMatcher.Kind.MATCHED, repeated.kind)
    }

    @Test fun distinctVoicesGetNewUnboundedIds() {
        val matcher = SpeakerIdentityMatcher(threshold = 0.8f)
        for (i in 0 until 17) {
            val vector = FloatArray(20)
            vector[i] = 1f
            val match = matcher.assign(listOf(vector))
            assertEquals("speaker_" + (i + 1).toString().padStart(2, '0'), match.speakerId)
        }
        assertEquals(17, matcher.speakerCount)
    }

    @Test fun ambiguousNearTieIsNotForcedToAnIdentity() {
        val matcher = SpeakerIdentityMatcher(threshold = 0.5f, ambiguityMargin = 0.1f)
        matcher.assign(listOf(floatArrayOf(1f, 0f, 0f)))
        matcher.assign(listOf(floatArrayOf(0f, 1f, 0f)))
        val result = matcher.assign(listOf(floatArrayOf(0.7071f, 0.7071f, 0f)))
        assertNull(result.speakerId)
        assertEquals(SpeakerIdentityMatcher.Kind.AMBIGUOUS, result.kind)
    }

    @Test fun insufficientEvidenceRemainsUnknown() {
        val matcher = SpeakerIdentityMatcher()
        val result = matcher.assign(emptyList())
        assertNull(result.speakerId)
        assertEquals(SpeakerIdentityMatcher.Kind.UNKNOWN, result.kind)
    }

    @Test fun embeddingsAreNormalizedBeforeMatching() {
        val matcher = SpeakerIdentityMatcher()
        val first = matcher.assign(listOf(floatArrayOf(10f, 0f)))
        val second = matcher.assign(listOf(floatArrayOf(1f, 0.01f)))
        assertEquals(first.speakerId, second.speakerId)
    }
}
