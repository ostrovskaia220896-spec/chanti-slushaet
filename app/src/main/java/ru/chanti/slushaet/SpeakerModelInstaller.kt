package ru.chanti.slushaet

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads pinned, offline speaker models and verifies their exact SHA-256 before use. */
object SpeakerModelInstaller {
    enum class Model(
        val fileName: String,
        val url: String,
        val sha256: String,
        val expectedBytes: Long
    ) {
        SEGMENTATION_INT8(
            "pyannote-segmentation-3-0.int8.onnx",
            "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/340b52f1f5cd12d45a30fa284691417eaad2ff92/model.int8.onnx",
            "10a438c2e0d90ed5f5da545cec2244d887315f6dbbbf1d3d564d00745b01952e",
            1540514L
        ),
        CAMPP_VOXCELEB_EN(
            "3dspeaker-campplus-voxceleb-en.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
            "98daf9bcf1c8abf3db374fec4c27a5eda7c58705bb3f180d057b085a72a88bb5",
            29596979L
        )
    }

    data class Models(val segmentation: File, val embedding: File)

    fun installed(context: Context): Boolean {
        val directory = File(context.filesDir, "speaker_models/sherpa-2.0.3")
        return Model.values().all { model ->
            File(directory, model.fileName).let { it.isFile && it.length() == model.expectedBytes }
        }
    }

    fun install(
        context: Context,
        onProgress: ((model: Model, downloaded: Long, total: Long) -> Unit)? = null
    ): Models {
        val segmentation = download(context, Model.SEGMENTATION_INT8, onProgress)
        val embedding = download(context, Model.CAMPP_VOXCELEB_EN, onProgress)
        return Models(segmentation, embedding)
    }

    fun download(
        context: Context,
        model: Model,
        onProgress: ((model: Model, downloaded: Long, total: Long) -> Unit)? = null
    ): File {
        val directory = File(context.filesDir, "speaker_models/sherpa-2.0.3")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create speaker model directory")
        }
        val target = File(directory, model.fileName)
        if (target.isFile && target.length() == model.expectedBytes &&
            sha256(target) == model.sha256) return target

        if (target.exists() && !target.delete()) throw IOException("Cannot replace invalid model")
        val temporary = File(directory, model.fileName + ".part")
        if (temporary.exists() && !temporary.delete()) throw IOException("Cannot clean partial model")

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ChantiSlushaet/1.0")
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Model download HTTP $status")
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Model download cancelled")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress?.invoke(model, downloaded, model.expectedBytes)
                        if (downloaded > model.expectedBytes) {
                            throw IOException("Model is larger than the pinned release")
                        }
                    }
                    output.fd.sync()
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (downloaded != model.expectedBytes || actualHash != model.sha256) {
                throw IOException("Model checksum/size mismatch for ${model.fileName}")
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot finalize downloaded model")
            return target
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
