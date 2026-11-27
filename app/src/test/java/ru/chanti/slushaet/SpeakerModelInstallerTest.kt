package ru.chanti.slushaet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpeakerModelInstallerTest {
    @Test fun modelPinsHaveExpectedSizesAndSha256Shape() {
        SpeakerModelInstaller.Model.values().forEach {
            assertEquals(64, it.sha256.length)
            assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(it.expectedBytes > 1_000_000L)
            assertTrue(it.url.startsWith("https://"))
        }
    }

    @Test fun sha256MatchesKnownVector() {
        val file = File.createTempFile("chanti-sha", ".txt")
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SpeakerModelInstaller.sha256(file)
            )
        } finally {
            file.delete()
        }
    }
}
