package com.openthinking.blankassistant.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import timber.log.Timber
import java.nio.ByteOrder

/**
 * Turns an audio file (our voice notes are AAC in .m4a) into 16 kHz mono floats in [-1, 1], which is
 * what the speech model wants. Uses only the platform's `MediaExtractor` and `MediaCodec`.
 *
 * Resampling is a box average: cheap, and it also acts as a rough low-pass, so speech stays clean.
 */
object PcmDecoder {

    const val RATE = 16_000

    fun decode(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = Resampler()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            out.configure(rate)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buffer = codec.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = codec.outputFormat
                        rate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        out.configure(rate)
                    }
                    o >= 0 -> {
                        val pcm = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        pcm.limit(info.size / 2)
                        while (pcm.remaining() >= channels) {
                            var sum = 0
                            repeat(channels) { sum += pcm.get() }
                            out.push(sum / channels / 32768f)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return out.finish()
        } catch (e: Exception) {
            Timber.e(e, "Could not decode $uri")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Box-average resampler to [RATE], fed one mono sample at a time. */
    private class Resampler {
        private var out = FloatArray(RATE * 30)
        private var size = 0
        private var ratio = 1.0
        private var edge = 1.0
        private var index = 0L
        private var acc = 0.0
        private var count = 0
        private var last = 0f

        fun configure(sourceRate: Int) {
            ratio = sourceRate.toDouble() / RATE
            edge = index + ratio
        }

        fun push(sample: Float) {
            acc += sample
            count++
            last = sample
            index++
            while (index >= edge) {
                emit(if (count > 0) (acc / count).toFloat() else last)
                acc = 0.0
                count = 0
                edge += ratio
            }
        }

        private fun emit(v: Float) {
            if (size == out.size) out = out.copyOf(out.size * 2)
            out[size++] = v
        }

        fun finish(): FloatArray {
            if (count > 0) emit((acc / count).toFloat())
            return out.copyOf(size)
        }
    }

    private const val TIMEOUT_US = 10_000L
}
