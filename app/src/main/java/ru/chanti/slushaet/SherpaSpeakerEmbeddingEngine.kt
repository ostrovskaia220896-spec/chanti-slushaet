package ru.chanti.slushaet

import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable
import java.io.File

/**
 * Extracts several clean voice embeddings for each local diarization cluster.
 * The caller keeps one SpeakerIdentityMatcher for the whole session, so local
 * speaker_00 labels can be reconciled between five-minute audio parts.
 */
class SherpaSpeakerEmbeddingEngine(
    modelFile: File,
    numThreads: Int = 2,
    private val minTurnMs: Long = 1800L,
    private val maxEmbeddingsPerSpeaker: Int = 3,
    private val maxSecondsPerEmbedding: Int = 6
) : Closeable {
    private val extractor: SpeakerEmbeddingExtractor

    init {
        require(modelFile.isFile && modelFile.length() > 0L) { "Speaker embedding model is missing" }
        require(numThreads in 1..8)
        require(minTurnMs >= 1000L)
        require(maxEmbeddingsPerSpeaker in 1..8)
        require(maxSecondsPerEmbedding in 2..10)
        extractor = SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(
                model = modelFile.absolutePath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu"
            )
        )
    }

    fun extractByLocalSpeaker(
        samples: FloatArray,
        turns: List<DetectedSpeakerTurn>
    ): Map<String, List<FloatArray>> {
        require(samples.isNotEmpty())
        val sampleCount = samples.size
        val grouped = turns.groupBy { it.speakerId }
        val result = linkedMapOf<String, List<FloatArray>>()
        for ((speakerId, candidates) in grouped) {
            val clean = candidates
                .filter { turn ->
                    turn.endMs - turn.startMs >= minTurnMs &&
                        turn.startMs >= 0L &&
                        turn.endMs <= sampleCount / 16L &&
                        !overlapsAnotherSpeaker(turn, turns)
                }
                .sortedByDescending { it.endMs - it.startMs }
                .take(maxEmbeddingsPerSpeaker)

            val vectors = mutableListOf<FloatArray>()
            for (turn in clean) {
                val start = (turn.startMs * 16L).toInt().coerceIn(0, sampleCount)
                val maxEnd = start + maxSecondsPerEmbedding * 16000
                val end = (turn.endMs * 16L).toInt().coerceIn(start, minOf(sampleCount, maxEnd))
                if (end - start < 16000) continue
                val segment = samples.copyOfRange(start, end)
                val stream: OnlineStream = extractor.createStream()
                try {
                    stream.acceptWaveform(segment, 16000)
                    stream.inputFinished()
                    if (extractor.isReady(stream)) {
                        val embedding = extractor.compute(stream)
                        if (embedding.isNotEmpty()) vectors.add(embedding)
                    }
                } finally {
                    stream.release()
                }
            }
            result[speakerId] = vectors
        }
        return result
    }

    private fun overlapsAnotherSpeaker(
        turn: DetectedSpeakerTurn,
        all: List<DetectedSpeakerTurn>
    ): Boolean = all.any { other ->
        other.speakerId != turn.speakerId &&
            other.startMs < turn.endMs && turn.startMs < other.endMs
    }

    override fun close() {
        extractor.release()
    }
}
