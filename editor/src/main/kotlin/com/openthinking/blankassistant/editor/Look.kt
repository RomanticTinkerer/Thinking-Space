package com.openthinking.blankassistant.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness5
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.FilterDrama
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.dot.gallery.feature.editor.adjustments.filters.ImageFilterTypes
import kotlin.math.max
import kotlin.math.pow

/** The sliders of the Adjust tab. Value ranges follow ReFra's tools (-1..1, or 0..1 for effects). */
internal enum class Tool(val label: String, val icon: ImageVector, val min: Float = -1f) {
    Brightness("Brightness", Icons.Outlined.Brightness5),
    Contrast("Contrast", Icons.Outlined.Contrast),
    Saturation("Saturation", Icons.Outlined.WaterDrop),
    Warmth("Warmth", Icons.Outlined.Thermostat),
    Tint("Tint", Icons.Outlined.Palette),
    Highlights("Highlights", Icons.Outlined.Layers),
    Shadows("Shadows", Icons.Outlined.FilterDrama),
    Vignette("Vignette", Icons.Outlined.Vignette, min = 0f),
    Sharpen("Sharpen", Icons.Outlined.Details, min = 0f),
}

/**
 * Everything the Adjust and Filters tabs can change, as plain data. It is rendered onto the working
 * bitmap by [renderLook]: a small copy for the live preview, the full image when committed.
 */
internal data class Look(
    val filter: ImageFilterTypes = ImageFilterTypes.Original,
    val intensity: Float = 1f,
    val values: Map<Tool, Float> = emptyMap(),
) {
    operator fun get(tool: Tool) = values[tool] ?: 0f
    fun with(tool: Tool, value: Float) = copy(values = values + (tool to value))
    val isDefault: Boolean get() = filter == ImageFilterTypes.Original && values.values.all { it == 0f }
}

private val IDENTITY = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

/** The preset's colour matrix blended with "no change", so [t] = 0 is the original and 1 is the full filter. */
internal fun filterMatrix(filter: ImageFilterTypes, t: Float): FloatArray? {
    val m = filter.createImageFilter().colorMatrix()?.values ?: return null
    return FloatArray(20) { IDENTITY[it] + (m[it] - IDENTITY[it]) * t }
}

/** All colour-only changes folded into one matrix (formulas from ReFra's variable filters). */
private fun colourMatrix(look: Look): ColorMatrix {
    val cm = ColorMatrix()
    filterMatrix(look.filter, look.intensity)?.let { cm.postConcat(ColorMatrix(it)) }
    val b = look[Tool.Brightness]
    if (b != 0f) cm.postConcat(ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, b * 255f,
        0f, 1f, 0f, 0f, b * 255f,
        0f, 0f, 1f, 0f, b * 255f,
        0f, 0f, 0f, 1f, 0f,
    )))
    val c = 1f + look[Tool.Contrast]
    if (c != 1f) cm.postConcat(ColorMatrix(floatArrayOf(
        c, 0f, 0f, 0f, 128f * (1 - c),
        0f, c, 0f, 0f, 128f * (1 - c),
        0f, 0f, c, 0f, 128f * (1 - c),
        0f, 0f, 0f, 1f, 0f,
    )))
    val s = 1f + look[Tool.Saturation]
    if (s != 1f) cm.postConcat(ColorMatrix().apply { setSaturation(s) })
    val w = look[Tool.Warmth]
    if (w != 0f) cm.postConcat(ColorMatrix(floatArrayOf(
        1f + w * 0.3f, 0f, 0f, 0f, 0f,
        0f, 1f + w * 0.1f, 0f, 0f, 0f,
        0f, 0f, 1f - w * 0.3f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    val t = look[Tool.Tint]
    if (t != 0f) cm.postConcat(ColorMatrix(floatArrayOf(
        1f + t * 0.1f, 0f, 0f, 0f, 0f,
        0f, 1f - t * 0.2f, 0f, 0f, 0f,
        0f, 0f, 1f + t * 0.1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    return cm
}

/** Returns a new bitmap with [look] applied. [src] is left untouched. */
internal fun renderLook(src: Bitmap, look: Look): Bitmap {
    if (look.isDefault) return src
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colourMatrix(look)) }
    Canvas(out).drawBitmap(src, 0f, 0f, paint)

    val hi = look[Tool.Highlights]
    val sh = look[Tool.Shadows]
    val sharpen = look[Tool.Sharpen]
    if (hi != 0f || sh != 0f || sharpen > 0f) {
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        if (hi != 0f || sh != 0f) toneCurve(px, hi, sh)
        if (sharpen > 0f) unsharp(px, out.width, out.height, sharpen)
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
    }

    val vignette = look[Tool.Vignette]
    if (vignette > 0f) {
        // Same radial gradient as ReFra's Vignette: clear to half way out, then darkening to the corners.
        val cx = out.width / 2f
        val cy = out.height / 2f
        val radius = max(cx, cy) * 1.2f
        val alpha = (vignette * 200f).toInt().coerceIn(0, 255)
        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(0x00000000, 0x00000000, Color.argb(alpha, 0, 0, 0)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        Canvas(out).drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply { this.shader = shader })
    }
    return out
}

/**
 * Highlights / Shadows as a per-channel curve: shadows lift (or sink) the dark end, highlights move
 * the bright end, and the middle barely changes. ReFra approximates these with a plain brightness
 * shift, which would make them identical to Brightness.
 */
private fun toneCurve(px: IntArray, highlights: Float, shadows: Float) {
    val lut = IntArray(256) { i ->
        val x = i / 255f
        val y = x + 0.35f * shadows * (1 - x).pow(2) * (1 - x) + 0.35f * highlights * x.pow(3)
        (y.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
    for (i in px.indices) {
        val p = px[i]
        px[i] = (p and 0xFF000000.toInt()) or
            (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
    }
}

/** Unsharp mask: add [amount] times the difference between each pixel and its 3x3 average. */
private fun unsharp(px: IntArray, w: Int, h: Int, amount: Float) {
    val src = px.copyOf()
    for (y in 0 until h) for (x in 0 until w) {
        var r = 0; var g = 0; var b = 0
        for (dy in -1..1) for (dx in -1..1) {
            val p = src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        val c = src[y * w + x]
        val nr = (((c shr 16) and 0xFF) + amount * 1.5f * (((c shr 16) and 0xFF) - r / 9f)).toInt().coerceIn(0, 255)
        val ng = (((c shr 8) and 0xFF) + amount * 1.5f * (((c shr 8) and 0xFF) - g / 9f)).toInt().coerceIn(0, 255)
        val nb = ((c and 0xFF) + amount * 1.5f * ((c and 0xFF) - b / 9f)).toInt().coerceIn(0, 255)
        px[y * w + x] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }
}
