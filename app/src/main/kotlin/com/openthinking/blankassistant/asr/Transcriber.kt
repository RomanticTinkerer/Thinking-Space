package com.openthinking.blankassistant.asr

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import timber.log.Timber

/**
 * Offline speech to text: OpenAI Whisper base.en (int8, English, about 160 MB) run by sherpa-onnx, with Silero
 * VAD cutting long recordings into speech chunks. Everything runs on the phone; nothing is sent anywhere.
 *
 * The model files are bundled as assets (the build downloads them, see `downloadAsr` in app/build.gradle.kts)
 * and sherpa reads them straight from the APK, so there is no second copy on the phone.
 *
 * The loaded model needs a few hundred MB of RAM, so open one only while there is a queue to work
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
        // Speech models drop words at abrupt starts and ends, so give each chunk a little silence on both sides.
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

        /** Only one run at a time process-wide: the model needs a few hundred MB of RAM. */
        val running = java.util.concurrent.atomic.AtomicBoolean(false)

        private const val WINDOW = 512 // Silero's frame at 16 kHz
        private const val MIN_CHUNK = PcmDecoder.RATE / 10
        private const val PRE_ROLL = PcmDecoder.RATE * 6 / 10
        private const val POST_ROLL = PcmDecoder.RATE * 3 / 10

        private const val DIR = "asr"

        /** Loads the model, or returns null (and logs) if it is missing from this build. */
        fun open(context: Context): Transcriber? = try {
            val recognizer = OfflineRecognizer(
                assetManager = context.assets,
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = "$DIR/encoder.int8.onnx",
                            decoder = "$DIR/decoder.int8.onnx",
                            language = "en",
                            task = "transcribe",
                        ),
                        tokens = "$DIR/tokens.txt",
                        numThreads = 4,
                    ),
                ),
            )
            val vad = Vad(
                assetManager = context.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "$DIR/silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.3f,
                        minSpeechDuration = 0.2f,
                        windowSize = WINDOW,
                        maxSpeechDuration = 20f, // Whisper reads at most 30 s at a time
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
    }
}
