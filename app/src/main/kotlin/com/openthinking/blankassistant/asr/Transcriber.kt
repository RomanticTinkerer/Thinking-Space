package com.openthinking.blankassistant.asr

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import timber.log.Timber
import java.io.File

/**
 * Offline speech to text: NVIDIA Parakeet TDT 0.6B v2 (int8, English) run by sherpa-onnx, with Silero VAD
 * cutting long recordings into speech chunks. Everything runs on the phone; nothing is sent anywhere.
 *
 * The model files are bundled as assets (the build downloads them, see `downloadAsr` in app/build.gradle.kts).
 * sherpa needs real file paths, so they are copied once to the app's private storage on first use.
 *
 * The loaded model is large (about a gigabyte of RAM), so open one only while there is a queue to work
 * through and [close] it afterwards.
 */
class Transcriber private constructor(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
) : AutoCloseable {

    /** The text of the audio at [uri], or null if it could not be decoded. "" means nothing was said. */
    fun transcribe(context: Context, uri: Uri): String? {
        val samples = PcmDecoder.decode(context, uri) ?: return null
        return transcribe(samples)
    }

    fun transcribe(samples: FloatArray): String {
        val parts = mutableListOf<String>()
        fun drain() {
            while (!vad.empty()) {
                val chunk = vad.front().samples
                vad.pop()
                if (chunk.size >= MIN_CHUNK) parts += decode(chunk)
            }
        }
        vad.reset()
        var i = 0
        while (i + WINDOW <= samples.size) {
            vad.acceptWaveform(samples.copyOfRange(i, i + WINDOW))
            i += WINDOW
            drain()
        }
        vad.flush()
        drain()
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun decode(chunk: FloatArray): String {
        // Parakeet drops words at abrupt starts and ends, so give each chunk a little silence on both sides.
        val padded = FloatArray(PRE_ROLL + chunk.size + POST_ROLL)
        chunk.copyInto(padded, PRE_ROLL)
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(padded, PcmDecoder.RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        vad.release()
        recognizer.release()
    }

    companion object {
        /** Settings switch (default on), kept in the same prefs as the other app settings. */
        const val PREF_KEY = "transcribe"

        fun enabled(context: Context): Boolean =
            context.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean(PREF_KEY, true)

        /** Only one run at a time process-wide: the model needs about a gigabyte of RAM. */
        val running = java.util.concurrent.atomic.AtomicBoolean(false)

        private const val WINDOW = 512 // Silero's frame at 16 kHz
        private const val MIN_CHUNK = PcmDecoder.RATE / 10
        private const val PRE_ROLL = PcmDecoder.RATE * 6 / 10
        private const val POST_ROLL = PcmDecoder.RATE * 3 / 10

        private const val ENCODER = "encoder.int8.onnx"
        private const val DECODER = "decoder.int8.onnx"
        private const val JOINER = "joiner.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val VAD = "silero_vad.onnx"

        /** Loads the model, or returns null (and logs) if it is missing from this build. */
        fun open(context: Context): Transcriber? = try {
            val dir = File(context.noBackupFilesDir, "asr").apply { mkdirs() }
            listOf(ENCODER, DECODER, JOINER, TOKENS, VAD).forEach { copyIfNeeded(context, it, File(dir, it)) }
            val recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(dir, ENCODER).path,
                            decoder = File(dir, DECODER).path,
                            joiner = File(dir, JOINER).path,
                        ),
                        tokens = File(dir, TOKENS).path,
                        numThreads = 4,
                        modelType = "nemo_transducer",
                    ),
                ),
            )
            val vad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(dir, VAD).path,
                        threshold = 0.5f,
                        minSilenceDuration = 0.3f,
                        minSpeechDuration = 0.2f,
                        windowSize = WINDOW,
                        maxSpeechDuration = 20f,
                    ),
                    sampleRate = PcmDecoder.RATE,
                    numThreads = 1,
                ),
            )
            Transcriber(recognizer, vad)
        } catch (e: Throwable) {
            Timber.e(e, "Speech model could not be loaded")
            null
        }

        private fun copyIfNeeded(context: Context, name: String, target: File) {
            val size = context.assets.openFd(name).use { it.length }
            if (target.length() == size) return
            val part = File(target.path + ".part")
            context.assets.open(name).use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } }
            check(part.renameTo(target)) { "Could not move $name into place" }
        }
    }
}
