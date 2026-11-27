package ru.chanti.slushaet

import com.k2fsa.sherpa.onnx.WaveReader
import java.io.Closeable
import java.io.File

/**
 * Processes one five-minute WAV part at a time while retaining a single identity
 * matcher for the complete game session. This avoids loading a multi-hour game
 * into one giant FloatArray and prevents per-part speaker numbering resets.
 */
class MagirSpeakerDiarizationPipeline(
    segmentationModel: File,
    embeddingModel: File,
    numThreads: Int = 2,
    clusterThreshold: Float = 0.5f,
    identityThreshold: Float = 0.5f,
    identityAmbiguityMargin: Float = 0.06f
) : Closeable {
    private val diarizer = SherpaSpeakerDiarizer(
        segmentationModel, embeddingModel, numThreads, clusterThreshold
    )
    private val embeddings = SherpaSpeakerEmbeddingEngine(
        embeddingModel, numThreads
    )
    private val identities = SpeakerIdentityMatcher(
        identityThreshold, identityAmbiguityMargin
    )

    val speakerCount: Int get() = identities.speakerCount

    fun processPart(wavFile: File): List<DetectedSpeakerTurn> {
        require(wavFile.isFile && wavFile.length() > 44L) { "Part WAV is missing or empty" }
        val wave = WaveReader.readWave(wavFile.absolutePath)
        require(wave.sampleRate == 16000) { "Expected 16 kHz mono WAV" }
        val samples = wave.samples
        val localTurns = diarizer.process(samples)
        val vectorsByLocalSpeaker = embeddings.extractByLocalSpeaker(samples, localTurns)
        val resolved = HashMap<String, String>()
        for ((localSpeaker, vectors) in vectorsByLocalSpeaker) {
            val match = identities.assign(vectors)
            resolved[localSpeaker] = match.speakerId ?: "unknown"
        }
        return localTurns.map { turn ->
            DetectedSpeakerTurn(
                startMs = turn.startMs,
                endMs = turn.endMs,
                speakerId = resolved[turn.speakerId] ?: "unknown",
                confidence = turn.confidence
            )
        }
    }

    override fun close() {
        try {
            embeddings.close()
        } finally {
            diarizer.close()
        }
    }
}
