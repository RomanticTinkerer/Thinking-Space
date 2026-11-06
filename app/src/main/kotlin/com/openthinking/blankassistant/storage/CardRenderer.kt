package com.openthinking.blankassistant.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.dot.gallery.R as EditorR
import com.openthinking.blankassistant.R
import com.openthinking.blankassistant.ui.DesignLanguages
import com.openthinking.blankassistant.ui.Face
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws the picture that stands in for a note, link or voice memo, so those live in the same album as
 * screenshots and get search, pin, remind and share for free. Dark, with the wallpaper's accent on
 * Android 12+ (baseline purple before), set in Google Sans Flex.
 *
 * ponytail: very long text is cut off on the card (the full text is kept as the caption). A real note
 * item type is the upgrade if cards feel wrong.
 */
object CardRenderer {

    private const val WIDTH = 1080
    private const val PAD = 84
    private const val MAX_HEIGHT = 1800
    private const val MIN_HEIGHT = 720

    fun text(context: Context, text: String): Bitmap {
        val clean = text.trim()
        val isLink = clean.startsWith("http://") || clean.startsWith("https://")
        val palette = Palette(context)
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.onCard
            applyFace(context, DesignLanguages.default.type.bodyLarge, if (clean.length < 90) 76f else 54f)
        }
        val label = labelPaint(context, palette)
        val date = datePaint(context, palette)

        val textWidth = WIDTH - 2 * PAD
        val top = PAD + label.textSize + 56f
        val bottom = PAD + date.textSize
        val budget = MAX_HEIGHT - top - bottom - 40f
        val lineHeight = body.fontMetrics.let { it.descent - it.ascent }
        val layout = StaticLayout.Builder.obtain(clean, 0, clean.length, body, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.08f)
            .setMaxLines(maxOf(1, (budget / (lineHeight * 1.08f)).toInt()))
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val height = (top + layout.height + bottom + 72f).toInt().coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        return draw(height, palette) { canvas ->
            canvas.drawText(if (isLink) "LINK" else "NOTE", PAD.toFloat(), PAD + label.textSize, label)
            canvas.save()
            canvas.translate(PAD.toFloat(), top)
            layout.draw(canvas)
            canvas.restore()
            canvas.drawText(stamp(), PAD.toFloat(), height - PAD.toFloat(), date)
        }
    }

    fun memo(context: Context, durationMs: Long): Bitmap {
        val palette = Palette(context)
        val label = labelPaint(context, palette)
        val date = datePaint(context, palette)
        val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.onCard
            applyFace(context, DesignLanguages.default.type.displayMedium, 150f)
        }
        val height = 900
        return draw(height, palette) { canvas ->
            canvas.drawText("VOICE MEMO", PAD.toFloat(), PAD + label.textSize, label)
            // Mic badge: a tonal disc with the mic icon in it.
            val cx = WIDTH / 2f
            val cy = height * 0.42f
            val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
            canvas.drawCircle(cx, cy, 170f, disc)
            context.getDrawable(R.drawable.ic_mic)?.mutate()?.let {
                it.setTint(palette.onAccent)
                it.setBounds((cx - 100).toInt(), (cy - 100).toInt(), (cx + 100).toInt(), (cy + 100).toInt())
                it.draw(canvas)
            }
            val time = clock(durationMs)
            canvas.drawText(time, cx - big.measureText(time) / 2f, cy + 170f + 56f + big.textSize, big)
            canvas.drawText(stamp(), PAD.toFloat(), height - PAD.toFloat(), date)
        }
    }

    private fun draw(height: Int, palette: Palette, content: (Canvas) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.card }
        canvas.drawRoundRect(RectF(24f, 24f, WIDTH - 24f, height - 24f), 90f, 90f, card)
        content(canvas)
        return bitmap
    }

    private fun labelPaint(context: Context, palette: Palette) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accent
        applyFace(context, DesignLanguages.default.type.labelLarge, 40f)
        letterSpacing = 0.14f
    }

    private fun datePaint(context: Context, palette: Palette) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.dim
        applyFace(context, DesignLanguages.default.type.labelMedium, 38f)
    }

    private fun TextPaint.applyFace(context: Context, face: Face, size: Float) {
        textSize = size
        typeface = context.resources.getFont(EditorR.font.google_sans_flex)
        fontVariationSettings = "'wght' ${face.weight}, 'wdth' ${face.width}, 'ROND' ${face.round}, 'opsz' ${face.opticalSize}"
    }

    private fun stamp(): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())

    private fun clock(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private class Palette(private val context: Context) {
        private val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        private fun pick(id: Int, fallback: Long): Int = if (dynamic) context.getColor(id) else fallback.toInt()

        val card = pick(android.R.color.system_accent1_900, 0xFF1D1B20)
        val accent = pick(android.R.color.system_accent1_200, 0xFFD0BCFF)
        val onAccent = pick(android.R.color.system_accent1_800, 0xFF381E72)
        val onCard = pick(android.R.color.system_neutral1_100, 0xFFE6E0E9)
        val dim = pick(android.R.color.system_neutral2_300, 0xFFCAC4D0)
    }
}
