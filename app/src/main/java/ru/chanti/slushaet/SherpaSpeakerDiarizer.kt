package ru.chanti.slushaet

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable
import java.io.File

/** One-session offline diarization. IDs are meaningful only within this full invocation. */
data class DetectedSpeakerTurn(
    val startMs: Long,
    val endMs: Long,
    val speakerId: String,
    val confidence: Float
)

/**
 * Wraps the official sherpa-onnx Android API.
 *
 * Input must be mono 16 kHz float PCM. Run once for the complete session to get
 * session-wide clusters; do not restart this engine independently for each
 * five-minute recording part unless a cross-part identity reconciliation layer
 * is also used.
 */
class SherpaSpeakerDiarizer(
    segmentationModel: File,
    embeddingModel: File,
    numThreads: Int = 2,
    clusterThreshold: Float = 0.5f
) : Closeable {
    private val diarizer: OfflineSpeakerDiarization

    init {
        require(segmentationModel.isFile && segmentationModel.length() > 0L) {
            "Speaker segmentation model is missing"
        }
        require(embeddingModel.isFile && embeddingModel.length() > 0L) {
            "Speaker embedding model is missing"
        }
        require(numThreads in 1..8) { "numThreads must be between 1 and 8" }
        require(clusterThreshold > 0f && clusterThreshold < 2f) {
            "clusterThreshold must be between 0 and 2"
        }

        val segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                model = segmentationModel.absolutePath,
                windowShiftRatio = 0.1f
            ),
            numThreads = numThreads,
            debug = false,
            provider = "cpu"
        )
        val embedding = SpeakerEmbeddingExtractorConfig(
            model = embeddingModel.absolutePath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu"
        )
        val clustering = FastClusteringConfig(
            numClusters = -1,
            threshold = clusterThreshold,
            computeConfidence = true
        )
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = segmentation,
            embedding = embedding,
            clustering = clustering,
            minDurationOn = 0.2f,
            minDurationOff = 0.5f
        )
        diarizer = OfflineSpeakerDiarization(config = config)
        require(diarizer.sampleRate() == 16000) {
            "Sherpa diarization model expects 16 kHz audio"
        }
    }

    fun process(
        samples: FloatArray,
        onProgress: ((processedChunks: Int, totalChunks: Int) -> Unit)? = null
    ): List<DetectedSpeakerTurn> {
        require(samples.isNotEmpty()) { "Audio is empty" }
        val segments = diarizer.processWithCallback(samples) { processed, total, _ ->
            onProgress?.invoke(processed, total)
            0
        }
        return segments
            .filter { it.start >= 0f && it.end > it.start && it.speaker >= 0 }
            .map {
                DetectedSpeakerTurn(
                    startMs = (it.start * 1000.0f).toLong(),
                    endMs = (it.end * 1000.0f).toLong(),
                    speakerId = "speaker_" + it.speaker.toString().padStart(2, '0'),
                    confidence = it.confidence
                )
            }
            .sortedBy { it.startMs }
    }

    override fun close() {
        diarizer.release()
    }
}
