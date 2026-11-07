package com.openthinking.blankassistant.storage

import android.content.Context

/**
 * Per-screenshot extras that MediaStore has no place for: a text note, a pin, a reminder time, the OCR text and the voice note's transcript.
 * Keyed by the screenshot's base name (`Screenshot<N>`), which is unique inside the album.
 */
class MemoryMeta(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("memory_meta", Context.MODE_PRIVATE)

    init {
        // Text from older readers is poorer. Drop it so those screenshots get scanned again.
        // Bump the "ocr4:" key whenever the reader gets better.
        prefs.all.keys.filter { it.startsWith("ocr") && !it.startsWith("ocr4:") }.takeIf { it.isNotEmpty() }
            ?.let { old -> prefs.edit().apply { old.forEach(::remove) }.apply() }
    }

    fun caption(base: String): String = prefs.getString("cap:$base", "").orEmpty()

    fun setCaption(base: String, text: String) = prefs.edit().apply {
        if (text.isBlank()) remove("cap:$base") else putString("cap:$base", text)
    }.apply()

    fun pinned(base: String): Boolean = prefs.getBoolean("pin:$base", false)

    fun setPinned(base: String, pinned: Boolean) = prefs.edit().apply {
        if (pinned) putBoolean("pin:$base", true) else remove("pin:$base")
    }.apply()

    /** Epoch millis of the pending reminder, or 0. */
    fun reminderAt(base: String): Long = prefs.getLong("rem:$base", 0L)

    fun setReminderAt(base: String, at: Long) = prefs.edit().apply {
        if (at > 0L) putLong("rem:$base", at) else remove("rem:$base")
    }.apply()

    /** Text OCR found in the image. null = not scanned yet, "" = scanned and nothing found. */
    fun ocr(base: String): String? = prefs.getString("ocr4:$base", null)

    fun setOcr(base: String, text: String) = prefs.edit().putString("ocr4:$base", text).apply()

    /** Forget all OCR text, so the gallery reads every screenshot again. */
    fun clearOcr() = prefs.edit().apply { prefs.all.keys.filter { it.startsWith("ocr4:") }.forEach(::remove) }.apply()

    /** What the voice note says. null = not transcribed yet, "" = transcribed and silent. */
    fun transcript(base: String): String? = prefs.getString("tr:$base", null)

    fun setTranscript(base: String, text: String) = prefs.edit().putString("tr:$base", text).apply()

    fun clearTranscript(base: String) = prefs.edit().remove("tr:$base").apply()

    fun forget(base: String) = prefs.edit()
        .remove("cap:$base").remove("pin:$base").remove("rem:$base").remove("ocr4:$base").remove("tr:$base").apply()
}
