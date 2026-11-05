package com.openthinking.blankassistant.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
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

    /** Recognised text (trimmed), or "" when there is none or the engine could not start. */
    fun read(bitmap: Bitmap): String {
        val api = tess ?: return ""
        return try {
            api.setImage(bitmap)
            api.utF8Text.orEmpty().trim()
        } finally {
            api.clear()
        }
    }

    override fun close() {
        tess?.recycle()
    }

    companion object {
        private const val LANG = "eng"

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
