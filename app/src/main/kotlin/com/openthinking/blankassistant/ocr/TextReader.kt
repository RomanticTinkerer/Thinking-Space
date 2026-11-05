package com.openthinking.blankassistant.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.googlecode.tesseract.android.ResultIterator
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import timber.log.Timber
import java.io.Closeable
import java.io.File

/**
 * Reads text out of a bitmap with Tesseract. Fully offline: the English model ships in the APK
 * assets and is copied to private storage once, because Tesseract needs a real file path.
 *
 * Not thread-safe. Make one reader per worker, call [read] as often as you like, then [close] it.
 */
class TextReader(context: Context) : Closeable {
    private val tess: TessBaseAPI? = open(context.applicationContext)

    /**
     * Recognised text, or "" when there is none or the engine could not start.
     *
     * Screenshots have small UI text, so the image is enlarged first (Tesseract wants letters
     * around 30 px tall). Words it is unsure about are dropped, which removes most icon noise.
     * Paragraphs are separated by a blank line, lines inside a paragraph by a line break.
     */
    fun read(bitmap: Bitmap): String {
        val api = tess ?: return ""
        val scale = minOf(SCALE, MAX_EDGE / maxOf(bitmap.width, bitmap.height))
        val big = if (scale > 1.05f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        var iterator: ResultIterator? = null
        return try {
            api.setImage(big)
            api.utF8Text // runs the recognition; the iterator below reads its result
            iterator = api.resultIterator
            iterator?.let(::collect).orEmpty()
        } finally {
            iterator?.delete()
            api.clear()
            if (big !== bitmap) big.recycle()
        }
    }

    private class Word(val text: String, val conf: Float)

    private fun collect(it: ResultIterator): String {
        val paragraphs = mutableListOf<MutableList<MutableList<Word>>>()
        it.begin()
        do {
            if (it.isAtBeginningOf(PageIteratorLevel.RIL_PARA) || paragraphs.isEmpty()) paragraphs += mutableListOf<MutableList<Word>>()
            if (it.isAtBeginningOf(PageIteratorLevel.RIL_TEXTLINE) || paragraphs.last().isEmpty()) paragraphs.last() += mutableListOf<Word>()
            val text = it.getUTF8Text(PageIteratorLevel.RIL_WORD).orEmpty().trim()
            if (text.isNotEmpty()) paragraphs.last().last() += Word(text, it.confidence(PageIteratorLevel.RIL_WORD))
        } while (it.next(PageIteratorLevel.RIL_WORD))

        return paragraphs.map { lines ->
            lines.mapNotNull(::cleanLine).joinToString("\n")
        }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** One line of text with the doubtful words removed, or null if too little is left to be real. */
    private fun cleanLine(words: List<Word>): String? {
        if (words.isEmpty()) return null
        val average = words.map { it.conf }.average()
        val solid = average >= GOOD_LINE
        val kept = words.filter {
            it.text.any(Char::isLetterOrDigit) && (it.text.length > 1 || it.text[0].isLetterOrDigit()) &&
                (it.conf >= WORD_CONFIDENCE || solid || (it.text == "I" && average >= 60)) &&
                // in a shaky line, 1-2 letter words are mostly icon debris, except "I"
                (solid || it.text.length > 2 || it.conf >= GOOD_LINE || it.text == "I")
        }
        val chars = kept.sumOf { w -> w.text.count(Char::isLetterOrDigit) }
        if (chars < 2 || (chars == 2 && !solid) || (!solid && kept.none { it.text.length > 2 })) return null
        return kept.joinToString(" ") { it.text }
    }

    override fun close() {
        tess?.recycle()
    }

    companion object {
        private const val LANG = "eng"
        private const val SCALE = 2f
        private const val MAX_EDGE = 5000f // keeps very tall screenshots from eating memory
        private const val WORD_CONFIDENCE = 50f
        private const val GOOD_LINE = 80.0

        private fun open(context: Context): TessBaseAPI? {
            val root = File(context.filesDir, "tesseract")
            val model = File(root, "tessdata/$LANG.traineddata")
            // The model ships inside the APK. Copy it out once. If you ever swap the model, rename it.
            if (!model.exists()) {
                model.parentFile?.mkdirs()
                val part = File(model.parentFile, "$LANG.part")
                context.assets.open("tessdata/$LANG.traineddata").use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                part.renameTo(model)
            }
            val api = TessBaseAPI()
            if (!api.init(root.absolutePath, LANG)) {
                Timber.e("Tesseract init failed")
                api.recycle()
                return null
            }
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            return api
        }

        /** Decodes an image into the software ARGB_8888 bitmap that Tesseract needs. */
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
