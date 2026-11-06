package com.openthinking.blankassistant.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import timber.log.Timber
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads text out of a bitmap with PaddleOCR (a text finder plus a text reader, both ONNX models in the
 * assets), run through ONNX Runtime. Fully offline.
 *
 * Not thread-safe. Make one reader per worker, call [read] as often as you like, then [close] it.
 */
class TextReader(context: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val models: Models? = open(env, context.applicationContext)

    private class Models(val det: OrtSession, val rec: OrtSession, val chars: List<String>)

    /** One line of text found in the image, in image pixels. */
    private class Piece(val left: Float, val top: Float, val right: Float, val bottom: Float, val text: String)

    /**
     * Recognised text, or "" when there is none or the engine could not run. Paragraphs are
     * separated by a blank line, lines inside a paragraph by a line break.
     */
    fun read(bitmap: Bitmap): String {
        val m = models ?: return ""
        return try {
            layout(find(m, bitmap).mapNotNull { recognise(m, bitmap, it) })
        } catch (e: Throwable) {
            // ponytail: an image that blows up (say, out of memory) is stored as "no text"; "Read again" retries it
            Timber.e(e, "OCR failed")
            ""
        }
    }

    // --- 1. find the text: a DB text detector gives a "how texty is this pixel" map ---

    /** Boxes (left, top, right, bottom in image pixels) around runs of text. */
    private fun find(m: Models, bitmap: Bitmap): List<FloatArray> {
        val w = bitmap.width
        val h = bitmap.height
        val edge = max(w, h)
        // Keep the real pixel size when we can: small UI text is the whole point. Only very tall images shrink.
        val s = when {
            edge > MAX_EDGE -> MAX_EDGE.toFloat() / edge
            edge < MIN_EDGE -> MIN_EDGE.toFloat() / edge
            else -> 1f
        }
        val nw = max(32, (w * s / 32f).roundToInt() * 32)
        val nh = max(32, (h * s / 32f).roundToInt() * 32)
        val scaled = if (nw == w && nh == h) bitmap else Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val plane = nw * nh
        val input = floatBuffer(3 * plane)
        val px = IntArray(plane)
        scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (scaled !== bitmap) scaled.recycle()
        // Channel order is BGR, as PaddleOCR itself feeds the model (it reads images with OpenCV).
        for (i in 0 until plane) {
            val c = px[i]
            input.put(i, ((c and 0xFF) / 255f - DET_MEAN[0]) / DET_STD[0])
            input.put(plane + i, (((c shr 8) and 0xFF) / 255f - DET_MEAN[1]) / DET_STD[1])
            input.put(2 * plane + i, (((c shr 16) and 0xFF) / 255f - DET_MEAN[2]) / DET_STD[2])
        }
        val prob = FloatArray(plane)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { tensor ->
            m.det.run(mapOf(m.det.inputNames.first() to tensor)).use { out ->
                val map = (out[0] as OnnxTensor).floatBuffer
                if (map.remaining() != plane) {
                    Timber.e("OCR detector returned %d values, expected %d", map.remaining(), plane)
                    return emptyList()
                }
                map.get(prob)
            }
        }
        return boxes(prob, nw, nh).map {
            floatArrayOf(it[0] * w / nw, it[1] * h / nh, it[2] * w / nw, it[3] * h / nh)
        }
    }

    /**
     * Turns the map into boxes: every blob of "texty" pixels becomes one box, grown outwards because the
     * detector draws text blobs a little smaller than the text. Blobs the model is unsure about are dropped.
     * ponytail: boxes are upright rectangles, which is all screenshots need. Tilted text would need rotated boxes.
     */
    private fun boxes(prob: FloatArray, w: Int, h: Int): List<FloatArray> {
        val seen = BooleanArray(prob.size)
        var stack = IntArray(1024)
        val out = ArrayList<FloatArray>()
        for (start in prob.indices) {
            if (seen[start] || prob[start] <= DET_THRESHOLD) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            var sum = 0.0
            var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                sum += prob[p]
                count++
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (seen[q] || prob[q] <= DET_THRESHOLD) continue
                    seen[q] = true
                    if (sp == stack.size) stack = stack.copyOf(sp * 2)
                    stack[sp++] = q
                }
            }
            val bw = (x1 - x0 + 1).toFloat()
            val bh = (y1 - y0 + 1).toFloat()
            if (min(bw, bh) < 3f || sum / count < BOX_SCORE) continue
            val grow = bw * bh * UNCLIP / (2 * (bw + bh))
            out += floatArrayOf(max(0f, x0 - grow), max(0f, y0 - grow), min(w.toFloat(), x1 + 1 + grow), min(h.toFloat(), y1 + 1 + grow))
        }
        return out
    }

    // --- 2. read each box: a CRNN gives a letter guess per slice, then CTC joins the slices ---

    private fun recognise(m: Models, bitmap: Bitmap, box: FloatArray): Piece? {
        val x0 = box[0].toInt().coerceIn(0, bitmap.width - 1)
        val y0 = box[1].toInt().coerceIn(0, bitmap.height - 1)
        val cw = (box[2].toInt() - x0).coerceAtMost(bitmap.width - x0)
        val ch = (box[3].toInt() - y0).coerceAtMost(bitmap.height - y0)
        if (cw < 2 || ch < 2) return null
        val crop = Bitmap.createBitmap(bitmap, x0, y0, cw, ch)
        val rw = ceil(REC_HEIGHT.toFloat() * cw / ch).toInt().coerceIn(16, MAX_REC_WIDTH)
        val line = Bitmap.createScaledBitmap(crop, rw, REC_HEIGHT, true)
        if (crop !== bitmap) crop.recycle()
        val plane = rw * REC_HEIGHT
        val px = IntArray(plane)
        line.getPixels(px, 0, rw, 0, 0, rw, REC_HEIGHT)
        line.recycle()
        val input = floatBuffer(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            input.put(i, ((c and 0xFF) / 255f - 0.5f) / 0.5f)
            input.put(plane + i, (((c shr 8) and 0xFF) / 255f - 0.5f) / 0.5f)
            input.put(2 * plane + i, (((c shr 16) and 0xFF) / 255f - 0.5f) / 0.5f)
        }
        var text = ""
        var confidence = 0f
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, REC_HEIGHT.toLong(), rw.toLong())).use { tensor ->
            m.rec.run(mapOf(m.rec.inputNames.first() to tensor)).use { out ->
                val t = out[0] as OnnxTensor
                val shape = t.info.shape
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                if (classes != m.chars.size) {
                    Timber.e("OCR dictionary has %d symbols, model has %d", m.chars.size, classes)
                    return null
                }
                val buf = t.floatBuffer
                val sb = StringBuilder()
                var sum = 0f
                var kept = 0
                var previous = 0
                for (step in 0 until steps) {
                    var best = 0
                    var bestP = buf.get(step * classes)
                    for (c in 1 until classes) {
                        val p = buf.get(step * classes + c)
                        if (p > bestP) { bestP = p; best = c }
                    }
                    if (best != 0 && best != previous) { sb.append(m.chars[best]); sum += bestP; kept++ }
                    previous = best
                }
                text = sb.toString().trim()
                confidence = if (kept > 0) sum / kept else 0f
            }
        }
        // Icons and textures also fire the detector. Their "text" is short and the reader is unsure about it.
        if (!text.any(Char::isLetterOrDigit) || confidence < MIN_CONFIDENCE || (text.length == 1 && confidence < 0.8f)) return null
        return Piece(box[0], box[1], box[2], box[3], text)
    }

    // --- 3. put the pieces back in reading order ---

    private fun layout(pieces: List<Piece>): String {
        class Row(var top: Float, var bottom: Float, val items: MutableList<Piece>)
        val rows = ArrayList<Row>()
        for (p in pieces.sortedBy { it.top + it.bottom }) {
            val height = p.bottom - p.top
            val row = rows.lastOrNull { r ->
                min(r.bottom, p.bottom) - max(r.top, p.top) > 0.5f * min(height, r.bottom - r.top)
            }
            if (row == null) rows += Row(p.top, p.bottom, mutableListOf(p)) else {
                row.items += p
                row.top = min(row.top, p.top)
                row.bottom = max(row.bottom, p.bottom)
            }
        }
        rows.sortBy { it.top }
        val sb = StringBuilder()
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                val gap = r.top - rows[i - 1].bottom
                sb.append(if (gap > PARAGRAPH_GAP * min(r.bottom - r.top, rows[i - 1].bottom - rows[i - 1].top)) "\n\n" else "\n")
            }
            sb.append(r.items.sortedBy { it.left }.joinToString(" ") { it.text })
        }
        return sb.toString()
    }

    override fun close() {
        models?.det?.close()
        models?.rec?.close()
    }

    companion object {
        private const val MIN_EDGE = 960 // tiny images are enlarged, the detector needs text above about 10 px
        private const val MAX_EDGE = 2560 // keeps very tall screenshots from eating memory
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val DET_THRESHOLD = 0.3f
        private const val BOX_SCORE = 0.6
        private const val UNCLIP = 1.6f
        private const val REC_HEIGHT = 48
        private const val MAX_REC_WIDTH = 2400
        private const val MIN_CONFIDENCE = 0.5f
        private const val PARAGRAPH_GAP = 0.6f

        private fun floatBuffer(size: Int): FloatBuffer =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun open(env: OrtEnvironment, context: Context): Models? = try {
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            // Symbol 0 is CTC's "nothing here"; the last one is the space character (it is not in the file).
            val dict = context.assets.open("ocr/dict.txt").bufferedReader(Charsets.UTF_8).readText().split('\n').dropLastWhile { it.isEmpty() }
            Models(
                det = env.createSession(context.assets.open("ocr/det.onnx").use { it.readBytes() }, options),
                rec = env.createSession(context.assets.open("ocr/rec.onnx").use { it.readBytes() }, options),
                chars = listOf("") + dict + " ",
            )
        } catch (e: Throwable) {
            Timber.e(e, "OCR models failed to load")
            null
        }

        /** Decodes an image into a software ARGB_8888 bitmap (so the pixels can be read). */
        fun decode(context: Context, uri: Uri): Bitmap? = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not decode %s for OCR", uri)
            null
        }
    }
}
