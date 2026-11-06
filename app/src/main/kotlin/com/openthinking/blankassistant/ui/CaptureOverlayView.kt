package com.openthinking.blankassistant.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dot.gallery.R as EditorR
import com.openthinking.blankassistant.R
import java.util.Locale

/**
 * Full-screen assistant overlay.
 *
 * When the screenshot arrives it is shown full-bleed (with a quick shutter flash), then morphs down
 * into a rounded thumbnail over a tinted backdrop. Below it sits a connected group of three tiles:
 * Edit (filled), Record and Save (tonal). Colours follow the wallpaper on Android 12+ (see [Palette]).
 *
 * It is a plain [View] tree with no AndroidX/Material dependency, because it lives in the
 * assistant session window rather than in an Activity.
 */
@SuppressLint("ViewConstructor")
class CaptureOverlayView(
    context: Context,
    private val listener: Listener,
) : FrameLayout(context) {

    interface Listener {
        fun onEdit()
        fun onRecordToggle()
        fun onRecordReset()
        fun onSave()
        fun onDismiss()
    }

    enum class NoteState { NONE, RECORDING, RECORDED }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density
    private val c = Palette(context)
    private val outerCorner = dp(DesignLanguages.default.heroCorner.value)
    private val innerCorner = dp(DesignLanguages.default.joinCorner.value + 2f)

    private val screenBounds = context.getSystemService(WindowManager::class.java)
        .maximumWindowMetrics.bounds
    private val screenW = screenBounds.width()
    private val screenH = screenBounds.height()

    private var bottomInset = 0

    // --- Views -------------------------------------------------------------------------------

    private val scrim = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c.backdropTop, c.backdropBottom))
        alpha = 0f
        setOnClickListener { listener.onDismiss() }
    }

    private val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }

    /** Holds the screenshot; this is the thing that morphs down. */
    private val frame = FrameLayout(context).apply {
        visibility = INVISIBLE
        // Swallow taps so touching the screenshot itself doesn't fall through to the dismiss scrim.
        isClickable = true
        addView(imageView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private val flash = View(context).apply {
        setBackgroundColor(Color.WHITE)
        alpha = 0f
    }

    private val message = TextView(context).apply {
        flex(DesignLanguages.default.type.bodyLarge)
        setTextColor(c.onSurface)
        textSize = 16f
        gravity = Gravity.CENTER
        visibility = GONE
        setPadding(dp(32f).toInt(), 0, dp(32f).toInt(), 0)
    }

    /** A small tonal chip above the buttons: recording time or "voice note attached". */
    private val statusText = TextView(context).apply {
        flex(DesignLanguages.default.type.labelLarge)
        setTextColor(c.secondary.content)
        textSize = 14f
        gravity = Gravity.CENTER
        visibility = GONE
        background = GradientDrawable().apply {
            cornerRadius = dp(100f)
            setColor(c.secondary.fill)
        }
        setPadding(dp(16f).toInt(), dp(7f).toInt(), dp(16f).toInt(), dp(7f).toInt())
    }
    private val editButton = ActionButton(R.drawable.ic_edit, R.string.action_edit, c.primary, Slot.FIRST).apply {
        setOnClickListener { listener.onEdit() }
    }
    private val recordButton = ActionButton(R.drawable.ic_mic, R.string.action_record, c.secondary, Slot.MIDDLE).apply {
        setOnClickListener { listener.onRecordToggle() }
    }
    private val saveButton = ActionButton(R.drawable.ic_save, R.string.action_save, c.tertiary, Slot.LAST).apply {
        setOnClickListener { listener.onSave() }
    }

    // While recording the group shows: [Stop] [Reset] [live level bars] instead of the three actions.
    private val stopButton = ActionButton(R.drawable.ic_stop, R.string.action_stop, c.error, Slot.FIRST).apply {
        setOnClickListener { listener.onRecordToggle() }
    }
    private val resetButton = ActionButton(R.drawable.ic_refresh, R.string.action_reset, c.secondary, Slot.MIDDLE).apply {
        setOnClickListener { listener.onRecordReset() }
    }
    private val visualizer = VisualizerView(context, c.primary.fill).apply {
        background = GradientDrawable().apply {
            cornerRadii = Slot.LAST.radii(outerCorner, innerCorner)
            setColor(c.surfaceHigh)
        }
    }
    private val recordingRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = GONE
        addView(stopButton, tile())
        addView(resetButton, tile())
        addView(visualizer, tile(1.3f))
    }

    private val pill = LinearLayout(context).apply {
        isClickable = true // gaps between the tiles must not dismiss
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(editButton, tile())
        addView(recordButton, tile())
        addView(saveButton, tile())
        addView(recordingRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun tile(weight: Float = 1f) = LinearLayout.LayoutParams(0, dp(TILE_DP).toInt(), weight).apply {
        marginStart = dp(1f).toInt()
        marginEnd = dp(1f).toInt()
    }

    /** Top-right corner of the framed screenshot: throw the capture away (nothing is saved until Save/Edit). */
    private val deleteButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_delete)
        imageTintList = ColorStateList.valueOf(c.onSurface)
        contentDescription = context.getString(R.string.action_discard)
        val pad = dp(10f).toInt()
        setPadding(pad, pad, pad, pad)
        background = RippleDrawable(
            ColorStateList.valueOf(c.ripple),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.surfaceHigh) },
            null,
        )
        alpha = 0f
        visibility = INVISIBLE
        setOnClickListener { listener.onDismiss() }
    }

    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = INVISIBLE
        alpha = 0f
        addView(
            statusText,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(14f).toInt() },
        )
        addView(pill, LinearLayout.LayoutParams(minOf(screenW - dp(32f).toInt(), dp(420f).toInt()), WRAP_CONTENT))
    }

    private var frameCornerRadius = 0f
    private var shown = false
    private var busy = false

    // End state of the capture animation, and the animators that lead there.
    private var endScale = 1f
    private var endTranslationY = 0f
    private var endRadius = 0f
    private var waiting: Animator? = null
    private var running: Animator? = null
    private val settleRunnable = Runnable { settle() }

    init {
        addView(scrim, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(frame, LayoutParams(screenW, screenH, Gravity.TOP or Gravity.START))
        addView(flash, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(deleteButton, LayoutParams(dp(DELETE_DP).toInt(), dp(DELETE_DP).toInt(), Gravity.TOP or Gravity.START))
        addView(message, LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        addView(
            controls,
            // MATCH_PARENT width: with WRAP_CONTENT the button group got squeezed to the status text's width.
            LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = dp(28f).toInt()
            },
        )

        // The session's content frame is wrap_content high; claim the whole screen explicitly.
        frame.pivotX = screenW / 2f
        frame.pivotY = screenH / 2f

        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.navigationBars())
            bottomInset = bars.bottom
            (controls.layoutParams as LayoutParams).bottomMargin = dp(28f).toInt() + bottomInset
            controls.requestLayout()
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.EXACTLY))
    }

    // --- Public API --------------------------------------------------------------------------

    /** Resets to the empty "waiting for screenshot" state: just a gently dimmed screen. */
    fun prepare() {
        stopAnimations()
        shown = false
        flash.alpha = 0f
        frame.scaleX = 1f
        frame.scaleY = 1f
        frame.translationY = 0f
        controls.translationY = 0f
        frame.visibility = INVISIBLE
        controls.visibility = INVISIBLE
        controls.alpha = 0f
        deleteButton.alpha = 0f
        deleteButton.visibility = INVISIBLE
        message.visibility = GONE
        controls.scaleX = 1f
        controls.scaleY = 1f
        deleteButton.scaleX = 1f
        deleteButton.scaleY = 1f
        for (button in listOf(editButton, recordButton, saveButton)) {
            button.alpha = 1f
            button.scaleX = 1f
            button.scaleY = 1f
        }
        scrim.alpha = 0f
        setNoteState(NoteState.NONE)
        setBusy(false)
        if (ValueAnimator.areAnimatorsEnabled()) {
            waiting = ObjectAnimator.ofFloat(scrim, ALPHA, 0f, SCRIM_ALPHA_WAITING).setDuration(160).apply { start() }
        } else {
            scrim.alpha = SCRIM_ALPHA_WAITING
        }
    }

    /** Plays the capture animation: full-bleed screenshot -> zooms out into the white frame. */
    fun showScreenshot(bitmap: Bitmap) {
        if (shown) return
        shown = true
        message.visibility = GONE
        imageView.setImageBitmap(bitmap)

        val scale = targetScale()
        val translationY = targetTranslationY()
        frameCornerRadius = outerCorner / scale
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, frameCornerRadius)
            }
        }
        frame.clipToOutline = true

        frame.scaleX = 1f
        frame.scaleY = 1f
        frame.translationY = 0f
        frame.visibility = VISIBLE
        controls.visibility = VISIBLE

        // Pin the delete button to the top-right corner of the scaled-down frame.
        val frameW = screenW * scale
        val frameTop = screenH / 2f + translationY - screenH * scale / 2f
        deleteButton.x = (screenW - frameW) / 2f + frameW - dp(DELETE_DP) - dp(12f)
        deleteButton.y = frameTop + dp(12f)
        deleteButton.visibility = VISIBLE

        endScale = scale
        endTranslationY = translationY
        endRadius = frameCornerRadius
        waiting?.cancel()
        waiting = null

        if (!ValueAnimator.areAnimatorsEnabled()) {
            settle()
            return
        }

        // One slow, smooth curve drives the whole morph, so the scale, position, corner radius and
        // backdrop move as a single thing instead of separate effects.
        val glide = PathInterpolator(0.2f, 0f, 0f, 1f)
        val fromRadius = cornerRadiusPx()
        val scrimFrom = scrim.alpha
        frameCornerRadius = fromRadius

        // The shutter flash and the backdrop are driven by this same animator (from its play time), so
        // they can never be left half-way if a separate animator stalls: the white flash used to stick.
        val morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MORPH_MS
            interpolator = glide
            addUpdateListener {
                val t = it.animatedValue as Float
                val ms = it.currentPlayTime.toFloat()
                val s = 1f + (endScale - 1f) * t
                frame.scaleX = s
                frame.scaleY = s
                frame.translationY = endTranslationY * t
                // The screen's own rounded corners ease into the thumbnail's corners.
                frameCornerRadius = fromRadius + (endRadius - fromRadius) * t
                frame.invalidateOutline()
                flash.alpha = (0.5f * (1f - ms / SHUTTER_MS)).coerceIn(0f, 0.5f)
                // The backdrop goes fully opaque quickly: nothing of the real screen may show around the frame.
                scrim.alpha = scrimFrom + (SCRIM_ALPHA_FINAL - scrimFrom) * (ms / BACKDROP_MS).coerceIn(0f, 1f)
            }
        }

        // Everything else just appears once the frame has mostly settled: the buttons fade up and grow
        // a little, then pop in one after another.
        val appearAt = (MORPH_MS * 0.45f).toLong()
        val pop = OvershootInterpolator(1.6f)
        val actionsIn = AnimatorSet().apply {
            val pieces = mutableListOf<Animator>(
                ObjectAnimator.ofFloat(controls, ALPHA, 0f, 1f).setDuration(240),
                ObjectAnimator.ofFloat(controls, SCALE_X, 0.9f, 1f).setDuration(420),
                ObjectAnimator.ofFloat(controls, SCALE_Y, 0.9f, 1f).setDuration(420),
                ObjectAnimator.ofFloat(controls, TRANSLATION_Y, dp(24f), 0f).setDuration(420),
                ObjectAnimator.ofFloat(deleteButton, ALPHA, 0f, 1f).setDuration(240).apply { startDelay = 140 },
                ObjectAnimator.ofFloat(deleteButton, SCALE_X, 0.5f, 1f).setDuration(380).apply { startDelay = 140; interpolator = pop },
                ObjectAnimator.ofFloat(deleteButton, SCALE_Y, 0.5f, 1f).setDuration(380).apply { startDelay = 140; interpolator = pop },
            )
            listOf(editButton, recordButton, saveButton).forEachIndexed { i, button ->
                button.alpha = 0f
                button.scaleX = 0.5f
                button.scaleY = 0.5f
                val delay = 120L + i * 70L
                pieces += ObjectAnimator.ofFloat(button, ALPHA, 0f, 1f).setDuration(200).apply { startDelay = delay }
                pieces += ObjectAnimator.ofFloat(button, SCALE_X, 0.5f, 1f).setDuration(420).apply { startDelay = delay; interpolator = pop }
                pieces += ObjectAnimator.ofFloat(button, SCALE_Y, 0.5f, 1f).setDuration(420).apply { startDelay = delay; interpolator = pop }
            }
            // Pieces without their own curve (the pop-ins keep their overshoot) glide like the morph.
            pieces.forEach { p ->
                if (p is ValueAnimator && p.interpolator is AccelerateDecelerateInterpolator) p.interpolator = glide
            }
            playTogether(pieces)
            startDelay = appearAt
        }

        controls.scaleX = 0.9f
        controls.scaleY = 0.9f
        deleteButton.scaleX = 0.5f
        deleteButton.scaleY = 0.5f

        running = AnimatorSet().apply {
            playTogether(listOf<Animator>(morph, actionsIn))
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (running === animation) settle()
                }
            })
            start()
        }
        // Safety net: if the animators never get frames (window not drawing yet), jump to the end state anyway.
        removeCallbacks(settleRunnable)
        postDelayed(settleRunnable, MORPH_MS + appearAt + 1000)
    }

    /** Jumps to the finished layout. Idempotent; also what runs when animations are off. */
    private fun settle() {
        removeCallbacks(settleRunnable)
        running?.let { running = null; it.cancel() }
        if (!shown) return
        flash.alpha = 0f
        scrim.alpha = SCRIM_ALPHA_FINAL
        frame.scaleX = endScale
        frame.scaleY = endScale
        frame.translationY = endTranslationY
        frameCornerRadius = endRadius
        frame.invalidateOutline()
        controls.alpha = if (busy) 0.5f else 1f
        controls.scaleX = 1f
        controls.scaleY = 1f
        controls.translationY = 0f
        deleteButton.alpha = 1f
        deleteButton.scaleX = 1f
        deleteButton.scaleY = 1f
        for (button in listOf(editButton, recordButton, saveButton)) {
            button.alpha = 1f
            button.scaleX = 1f
            button.scaleY = 1f
        }
        invalidate()
    }

    private fun stopAnimations() {
        removeCallbacks(settleRunnable)
        waiting?.cancel()
        waiting = null
        running?.let { running = null; it.cancel() }
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        flash.alpha = 0f
        super.onDetachedFromWindow()
    }

    /** The phone's real screen corner radius when Android reports it (12+), otherwise a typical one. */
    private fun cornerRadiusPx(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = rootWindowInsets
            val radii = listOf(
                RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT,
            ).mapNotNull { insets?.getRoundedCorner(it)?.radius }
            if (radii.isNotEmpty()) return radii.average().toFloat()
        }
        return dp(28f)
    }

    /** No screenshot could be taken (setting off, secure screen...): explain, tap anywhere to close. */
    fun showUnavailable() {
        if (shown) return
        message.setText(R.string.msg_screenshot_unavailable)
        message.visibility = VISIBLE
        scrim.alpha = SCRIM_ALPHA_MESSAGE
    }

    fun setNoteState(state: NoteState, durationMs: Long = 0L) {
        val recording = state == NoteState.RECORDING
        recordingRow.visibility = if (recording) VISIBLE else GONE
        editButton.visibility = if (recording) GONE else VISIBLE
        recordButton.visibility = if (recording) GONE else VISIBLE
        saveButton.visibility = if (recording) GONE else VISIBLE
        when (state) {
            NoteState.NONE -> {
                recordButton.set(R.drawable.ic_mic, R.string.action_record)
                statusText.visibility = GONE
            }
            NoteState.RECORDING -> {
                visualizer.clear()
                statusText.text = context.getString(R.string.status_recording, formatDuration(durationMs))
                statusText.visibility = VISIBLE
            }
            NoteState.RECORDED -> {
                recordButton.set(R.drawable.ic_mic, R.string.action_rerecord)
                statusText.text = context.getString(R.string.status_note_attached, formatDuration(durationMs))
                statusText.visibility = VISIBLE
            }
        }
    }

    /** Live feedback while recording: timer text plus the level bars. */
    fun setRecordingProgress(durationMs: Long, amplitude: Int) {
        statusText.text = context.getString(R.string.status_recording, formatDuration(durationMs))
        visualizer.push((amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f))
    }

    fun setBusy(busy: Boolean) {
        this.busy = busy
        controls.alpha = if (busy) 0.5f else if (controls.visibility == VISIBLE && shown) 1f else controls.alpha
        editButton.isEnabled = !busy
        recordButton.isEnabled = !busy
        saveButton.isEnabled = !busy
        stopButton.isEnabled = !busy
        deleteButton.isEnabled = !busy
        resetButton.isEnabled = !busy
    }

    /** Google Sans Flex with the design language's axes for this text style. */
    private fun TextView.flex(face: Face) {
        typeface = resources.getFont(EditorR.font.google_sans_flex)
        fontVariationSettings =
            "'wght' ${face.weight}, 'wdth' ${face.width}, 'ROND' ${face.round}, 'opsz' ${face.opticalSize}"
    }

    // --- Geometry ----------------------------------------------------------------------------

    private fun controlsReservePx(): Float = dp(CONTROLS_RESERVE_DP) + bottomInset
    private fun topMarginPx(): Float = dp(TOP_MARGIN_DP)

    /** Largest scale (capped) at which the framed screenshot still clears the action pill. */
    private fun targetScale(): Float {
        val available = screenH - controlsReservePx() - topMarginPx()
        return (available / screenH).coerceIn(0.4f, MAX_SCALE)
    }

    /** Vertical shift that centres the scaled frame in the space above the pill. */
    private fun targetTranslationY(): Float {
        val available = screenH - controlsReservePx() - topMarginPx()
        val centreY = topMarginPx() + available / 2f
        return centreY - screenH / 2f
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    // --- Parts -------------------------------------------------------------------------------

    /** Scrolling mic-level bars: newest level on the right. */
    private class VisualizerView(context: Context, barColor: Int) : View(context) {
        private val levels = FloatArray(BARS)
        private var head = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = barColor
            strokeCap = Paint.Cap.ROUND
        }

        fun push(level: Float) {
            levels[head] = kotlin.math.sqrt(level) // sqrt: speech sits low on a linear scale
            head = (head + 1) % BARS
            invalidate()
        }

        fun clear() {
            levels.fill(0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val slot = width / BARS.toFloat()
            paint.strokeWidth = slot * 0.5f
            val cy = height / 2f
            for (i in 0 until BARS) {
                val h = maxOf(levels[(head + i) % BARS] * height, paint.strokeWidth)
                val x = slot * (i + 0.5f)
                canvas.drawLine(x, cy - h / 2f + paint.strokeWidth / 2f, x, cy + h / 2f - paint.strokeWidth / 2f, paint)
            }
        }

        private companion object {
            const val BARS = 28
        }
    }

    /** Tile in the connected button group: icon over a caption, big corners outside and small ones where it joins. */
    private inner class ActionButton(
        icon: Int,
        label: Int,
        private val tone: Tone,
        slot: Slot,
    ) : LinearLayout(context) {

        private val iconView = ImageView(context)
        private val labelView = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            fun shape(color: Int) = GradientDrawable().apply {
                cornerRadii = slot.radii(outerCorner, innerCorner)
                setColor(color)
            }
            background = RippleDrawable(ColorStateList.valueOf(tone.content and 0x00FFFFFF or 0x33000000), shape(tone.fill), shape(Color.WHITE))

            val iconSize = dp(26f).toInt()
            iconView.imageTintList = ColorStateList.valueOf(tone.content)
            addView(iconView, LayoutParams(iconSize, iconSize))

            labelView.flex(DesignLanguages.default.type.labelMedium)
            labelView.setTextColor(tone.content)
            labelView.textSize = 13f
            labelView.gravity = Gravity.CENTER
            labelView.maxLines = 1
            addView(labelView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(5f).toInt() })

            set(icon, label)
        }

        fun set(icon: Int, label: Int) {
            iconView.setImageResource(icon)
            labelView.setText(label)
            contentDescription = context.getString(label)
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.6f
        }
    }

    private class Tone(val fill: Int, val content: Int)

    /** Where a tile sits in the group: only the outer ends get the big corner. */
    private enum class Slot {
        FIRST, MIDDLE, LAST;

        fun radii(outer: Float, inner: Float): FloatArray {
            val l = if (this == FIRST) outer else inner
            val r = if (this == LAST) outer else inner
            return floatArrayOf(l, l, r, r, r, r, l, l)
        }
    }

    /**
     * Dark Material 3 colours. On Android 12+ they come from the wallpaper's tonal palettes (the same
     * ones the app's dynamic colour uses); older phones get the baseline purple.
     */
    private class Palette(private val context: Context) {
        private val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        private fun pick(id: Int, fallback: Long): Int =
            if (dynamic) context.getColor(id) else fallback.toInt()

        val backdropTop = pick(android.R.color.system_neutral1_900, 0xFF141218)
        val backdropBottom = pick(android.R.color.system_accent1_900, 0xFF2B2236)
        val surfaceHigh = pick(android.R.color.system_neutral1_800, 0xFF2B2930)
        val onSurface = pick(android.R.color.system_neutral1_100, 0xFFE6E0E9)
        val ripple = pick(android.R.color.system_neutral1_100, 0xFFE6E0E9) and 0x00FFFFFF or 0x33000000
        val primary = Tone(
            pick(android.R.color.system_accent1_200, 0xFFD0BCFF),
            pick(android.R.color.system_accent1_800, 0xFF381E72),
        )
        val secondary = Tone(
            pick(android.R.color.system_accent2_700, 0xFF4A4458),
            pick(android.R.color.system_accent2_100, 0xFFE8DEF8),
        )
        val tertiary = Tone(
            pick(android.R.color.system_accent3_700, 0xFF633B48),
            pick(android.R.color.system_accent3_100, 0xFFFFD8E4),
        )
        val error = Tone(0xFFF2B8B5.toInt(), 0xFF601410.toInt()) // red stays red: it means "recording"
    }

    private companion object {
        const val DELETE_DP = 44f
        const val TILE_DP = 76f
        const val TOP_MARGIN_DP = 56f
        const val CONTROLS_RESERVE_DP = 176f
        const val MAX_SCALE = 0.66f
        const val SCRIM_ALPHA_WAITING = 0.25f
        const val SCRIM_ALPHA_FINAL = 1f // opaque: the real screen must not show behind the thumbnail
        const val SCRIM_ALPHA_MESSAGE = 0.72f
        const val MORPH_MS = 900L
        const val SHUTTER_MS = 140f
        const val BACKDROP_MS = 200f
        const val MAX_AMPLITUDE = 32767f
    }
}
