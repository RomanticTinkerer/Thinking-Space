package com.openthinking.blankassistant.storage

import android.content.Context

/**
 * Per-screenshot extras that MediaStore has no place for: a text note, a pin, a reminder time and the OCR text.
 * Keyed by the screenshot's base name (`Screenshot<N>`), which is unique inside the album.
 */
class MemoryMeta(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("memory_meta", Context.MODE_PRIVATE)

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
    fun ocr(base: String): String? = prefs.getString("ocr:$base", null)

    fun setOcr(base: String, text: String) = prefs.edit().putString("ocr:$base", text).apply()

    fun forget(base: String) =
        prefs.edit().remove("cap:$base").remove("pin:$base").remove("rem:$base").remove("ocr:$base").apply()
}
