package ru.chanti.slushaet

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileWriter
import java.util.Locale

/** Runs Whisper.cpp offline; one model loaded for all five-minute session fragments. */
class WhisperEngine(private val ctx: Context, private val path: String) : AutoCloseable {
    private var model: WhisperModel? = null

    fun open() = runBlocking {
        model = Whisper.loadModel(ctx, path)
    }

    fun transcribe(wav: File, target: File, offsetSeconds: Double) = runBlocking {
        val m = model ?: throw IllegalStateException("Whisper model not loaded")
        val result = Whisper.transcribe(
            m, wav.absolutePath,
            WhisperConfig(language = "ru", translate = false, threads = 4)
        )
        FileWriter(target, false).use { file ->
            if (result.segments.isEmpty()) {
                if (result.text.isNotBlank()) {
                    file.write(format(offsetSeconds, result.text))
                }
            } else {
                for (segment in result.segments) {
                    if (segment.text.isBlank()) continue
                    val sec = offsetSeconds + (segment.startMs / 1000.0)
                    file.write(format(sec, segment.text))
                }
            }
        }
    }

    private fun format(seconds: Double, text: String): String {
        val t = seconds.toLong().coerceAtLeast(0L)
        return String.format(
            Locale.US, "[%02d:%02d:%02d] %s\n",
            t / 3600, (t / 60) % 60, t % 60,
            text.trim().replace(Regex("[\r\n]+"), " ")
        )
    }

    override fun close() {
        model?.let { Whisper.releaseModel(it) }
        model = null
    }
}
