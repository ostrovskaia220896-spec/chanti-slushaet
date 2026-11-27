package ru.chanti.slushaet

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileWriter
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Runs Whisper.cpp offline; one model loaded for all five-minute session fragments. */
class WhisperEngine(private val ctx: Context, private val path: String) : AutoCloseable {
    private var model: WhisperModel? = null

    fun open() = runBlocking {
        model = Whisper.loadModel(ctx, path)
    }

    fun transcribe(wav: File, target: File, timedTarget: File, offsetSeconds: Double) = runBlocking {
        val m = model ?: throw IllegalStateException("Whisper model not loaded")
        val result = Whisper.transcribe(
            m, wav.absolutePath,
            WhisperConfig(language = "ru", translate = false, threads = 4)
        )
        val spans = JSONArray()
        val untimed = JSONArray()
        FileWriter(target, false).use { file ->
            if (result.segments.isEmpty()) {
                if (result.text.isNotBlank()) {
                    file.write(format(offsetSeconds, result.text))
                    untimed.put(result.text)
                }
            } else {
                for (segment in result.segments) {
                    if (segment.text.isBlank()) continue
                    val sec = offsetSeconds + (segment.startMs / 1000.0)
                    file.write(format(sec, segment.text))
                    val start = segment.startMs.toLong()
                    val end = segment.endMs.toLong()
                    if (start >= 0 && end > start) {
                        spans.put(JSONObject().put("startMs", start).put("endMs", end)
                            .put("text", segment.text))
                    } else {
                        untimed.put(segment.text)
                    }
                }
            }
        }
        // Local times are relative to the audio part; offset is the accumulated
        // duration of finalized parts, not wall-clock recording time.
        val data = JSONObject().put("schema", 1)
            .put("offsetMs", Math.round(offsetSeconds * 1000.0))
            .put("segments", spans).put("untimedText", untimed)
        FileOutputStream(timedTarget, false).use {
            it.write(data.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
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
