package com.openthinking.blankassistant.editor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.Closeable
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * On-device AI for the editor. Both models ship in the APK (assets/models), so nothing is downloaded.
 *
 *  - MobileSAM (Apache-2.0): tap a subject, get its outline. Image encoder + point-prompt decoder, as
 *    exported by Acly/MobileSAM. Pipeline follows ReFra's CutoutHelper (Apache-2.0): scale the long
 *    edge to 1024, top-left pad, run the encoder once, then run the small decoder per tap.
 *  - UltraFace RFB-320 (MIT): finds faces, follows ReFra's FaceHelper.
 *
 * Runtime: ONNX Runtime (MIT).
 */

private fun session(env: OrtEnvironment, context: Context, asset: String): OrtSession {
    val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
    }
    // Read into memory instead of copying to a file first. The biggest model is 28 MB.
    val bytes = context.assets.open("models/$asset").use { it.readBytes() }
    return try {
        env.createSession(bytes, options)
    } finally {
        options.close()
    }
}

/** One tap on the image, in image pixels. [add] = false marks a spot that is NOT part of the subject. */
internal data class Tap(val x: Float, val y: Float, val add: Boolean)

/**
 * A subject mask: [logits] has one value per pixel of the image, above 0 means "inside".
 * Kept as raw logits so the edge can be anti-aliased instead of a hard on/off cut.
 */
internal class Mask(val width: Int, val height: Int, val logits: FloatArray) {
    fun alpha(i: Int): Float = 1f / (1f + exp(-logits[i]))

    /** Half-resolution-ish tinted picture of the mask for drawing over the image. */
    fun overlay(tint: Int): Bitmap {
        val step = max(1, max(width, height) / 1024)
        val ow = width / step
        val oh = height / step
        val px = IntArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            if (logits[y * step * width + x * step] > 0f) px[y * ow + x] = tint
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }

    private fun bounds(): Rect? {
        var minX = width; var maxX = -1; var minY = height; var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (logits[y * width + x] > 0f) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /** The subject on a transparent background, cropped to its outline. */
    fun cutout(source: Bitmap): Bitmap? {
        val r = bounds() ?: return null
        val w = r.width()
        val h = r.height()
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, r.left, r.top, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = (alpha((r.top + y) * width + r.left + x) * 255f).roundToInt()
            val i = y * w + x
            px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The subject stays sharp, everything else is blurred. */
    fun blurBackground(source: Bitmap): Bitmap {
        val blurred = blurred(source, 14)
        val a = IntArray(width * height)
        val b = IntArray(width * height)
        source.getPixels(a, 0, width, 0, 0, width, height)
        blurred.getPixels(b, 0, width, 0, 0, width, height)
        for (i in a.indices) {
            val t = alpha(i)
            val p = a[i]
            val q = b[i]
            fun mix(shift: Int) = ((((p shr shift) and 0xFF) * t + ((q shr shift) and 0xFF) * (1 - t)).roundToInt())
            a[i] = (p and 0xFF000000.toInt()) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        return Bitmap.createBitmap(a, width, height, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Blur by shrinking and stretching back. Not a true gaussian, but smooth enough and very cheap.
 * [strength] is the shrink factor, bigger = blurrier.
 */
internal fun blurred(src: Bitmap, strength: Int): Bitmap {
    val w = max(1, src.width / strength)
    val h = max(1, src.height / strength)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val big = Bitmap.createScaledBitmap(small, src.width, src.height, true)
    if (small !== big) small.recycle()
    return big.copy(Bitmap.Config.ARGB_8888, true)
}

/** Holds MobileSAM for one image: encodes it once, then answers taps quickly. Close it when done. */
internal class SubjectPicker(private val context: Context, private val image: Bitmap) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private var decoder: OrtSession? = null
    private var embeddings: OnnxTensor? = null
    private val scale = 1024f / max(image.width, image.height)

    /** Slow (seconds). Call off the main thread. */
    fun prepare() {
        val w = (image.width * scale).roundToInt()
        val h = (image.height * scale).roundToInt()
        val resized = Bitmap.createScaledBitmap(image, w, h, true)
        val px = IntArray(w * h)
        resized.getPixels(px, 0, w, 0, 0, w, h)
        if (resized !== image) resized.recycle()
        // [1024, 1024, 3], RGB 0..255 (the export normalises inside), padded bottom/right with zeros.
        val input = FloatArray(1024 * 1024 * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val i = (y * 1024 + x) * 3
            input[i] = ((c shr 16) and 0xFF).toFloat()
            input[i + 1] = ((c shr 8) and 0xFF).toFloat()
            input[i + 2] = (c and 0xFF).toFloat()
        }
        val encoder = session(env, context, "mobile_sam_image_encoder.onnx")
        try {
            // The Acly export takes [1024, 1024, 3] with no batch dimension.
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1024, 1024, 3)).use { tensor ->
                encoder.run(Collections.singletonMap("input_image", tensor)).use { out ->
                    val t = out.get(0) as OnnxTensor
                    val copy = FloatArray(t.floatBuffer.capacity()).also { t.floatBuffer.rewind(); t.floatBuffer.get(it) }
                    embeddings = OnnxTensor.createTensor(env, FloatBuffer.wrap(copy), t.info.shape)
                }
            }
        } finally {
            encoder.close()
        }
        decoder = session(env, context, "sam_mask_decoder_single.onnx")
    }

    /** Fast. Returns null when the model finds nothing at those taps. */
    fun pick(taps: List<Tap>): Mask? {
        val dec = decoder ?: return null
        val emb = embeddings ?: return null
        if (taps.isEmpty()) return null
        // The decoder wants at least two points; a lone tap gets a "padding" point labelled -1.
        val n = max(2, taps.size)
        val coords = FloatArray(n * 2)
        val labels = FloatArray(n) { -1f }
        taps.forEachIndexed { i, t ->
            coords[i * 2] = t.x * scale
            coords[i * 2 + 1] = t.y * scale
            labels[i] = if (t.add) 1f else 0f
        }
        val w = image.width
        val h = image.height
        val closeables = mutableListOf<AutoCloseable>()
        fun <T : AutoCloseable> T.track(): T = also { closeables += it }
        try {
            val inputs = mapOf(
                "image_embeddings" to emb,
                "point_coords" to OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, n.toLong(), 2)).track(),
                "point_labels" to OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, n.toLong())).track(),
                "mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256)).track(),
                "has_mask_input" to OnnxTensor.createTensor(env, floatArrayOf(0f)).track(),
                "orig_im_size" to OnnxTensor.createTensor(env, floatArrayOf(h.toFloat(), w.toFloat())).track(),
            )
            dec.run(inputs).use { result ->
                val masks = result.get(0) as OnnxTensor
                val buffer = masks.floatBuffer
                val logits = FloatArray(w * h)
                buffer.rewind()
                buffer.get(logits, 0, min(logits.size, buffer.remaining()))
                return if (logits.any { it > 0f }) Mask(w, h, logits) else null
            }
        } finally {
            closeables.forEach { it.close() }
        }
    }

    override fun close() {
        runCatching { decoder?.close() }
        runCatching { embeddings?.close() }
        decoder = null
        embeddings = null
    }
}

/** Finds faces and blurs them. Returns the new bitmap and how many faces were blurred. */
internal fun blurFaces(context: Context, image: Bitmap): Pair<Bitmap, Int> {
    val env = OrtEnvironment.getEnvironment()
    val session = session(env, context, "version-RFB-320.onnx")
    try {
        val inW = 320
        val inH = 240
        val small = Bitmap.createScaledBitmap(image, inW, inH, true)
        val px = IntArray(inW * inH)
        small.getPixels(px, 0, inW, 0, 0, inW, inH)
        if (small !== image) small.recycle()
        // NCHW, RGB, (value - 127) / 128
        val input = FloatBuffer.allocate(3 * inW * inH)
        for (shift in intArrayOf(16, 8, 0)) for (c in px) input.put((((c shr shift) and 0xFF) - 127f) / 128f)
        input.rewind()

        val boxes = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, inH.toLong(), inW.toLong())).use { tensor ->
            session.run(Collections.singletonMap(session.inputNames.first(), tensor)).use { out ->
                var scores: Array<FloatArray>? = null
                var rects: Array<FloatArray>? = null
                for (i in 0 until out.size()) {
                    @Suppress("UNCHECKED_CAST")
                    val a = (out[i].value as Array<Array<FloatArray>>)[0]
                    // Scores end in 2 values per anchor, boxes in 4.
                    when (a.firstOrNull()?.size) { 2 -> scores = a; 4 -> rects = a }
                }
                val s = scores ?: return image to 0
                val r = rects ?: return image to 0
                s.indices.filter { s[it][1] >= 0.7f }
                    .map { RectF(r[it][0], r[it][1], r[it][2], r[it][3]) to s[it][1] }
            }
        }
        val faces = nonMaxSuppression(boxes)
        if (faces.isEmpty()) return image to 0

        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val soft = blurred(image, 12)
        faces.forEach { f ->
            // Grow the box a little: the detector hugs the face tightly.
            val padX = (f.right - f.left) * 0.15f
            val padY = (f.bottom - f.top) * 0.15f
            val rect = Rect(
                ((f.left - padX).coerceIn(0f, 1f) * image.width).toInt(),
                ((f.top - padY).coerceIn(0f, 1f) * image.height).toInt(),
                ((f.right + padX).coerceIn(0f, 1f) * image.width).toInt(),
                ((f.bottom + padY).coerceIn(0f, 1f) * image.height).toInt(),
            )
            if (rect.width() > 1 && rect.height() > 1) canvas.drawBitmap(soft, rect, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        soft.recycle()
        return out to faces.size
    } finally {
        session.close()
    }
}

private fun nonMaxSuppression(candidates: List<Pair<RectF, Float>>): List<RectF> {
    val sorted = candidates.sortedByDescending { it.second }.toMutableList()
    val kept = mutableListOf<RectF>()
    while (sorted.isNotEmpty()) {
        val best = sorted.removeAt(0).first
        kept += best
        sorted.removeAll { iou(best, it.first) > 0.3f }
    }
    return kept
}

private fun iou(a: RectF, b: RectF): Float {
    val iw = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
    val ih = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
    val inter = iw * ih
    val union = a.width() * a.height() + b.width() * b.height() - inter
    return if (union <= 0f) 0f else inter / union
}
