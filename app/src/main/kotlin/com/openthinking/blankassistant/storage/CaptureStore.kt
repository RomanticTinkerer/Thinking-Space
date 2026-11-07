package com.openthinking.blankassistant.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Writes captures into the shared MediaStore, so they show up in every gallery / recorder app.
 * On Android 10+ an app can add and read back its own media this way without any storage permission.
 *
 * Layout:
 * - screenshots: `Pictures/OpenThinking Space Screenshots/Screenshot<N>.png`  (its own album)
 * - voice notes: `Recordings/OTSVoiceNotes/Screenshot<N>_Note.m4a`            (`Music/` before Android 12)
 *
 * A note belongs to the screenshot with the same `Screenshot<N>` base name; that is the pairing.
 */
object CaptureStore {

    const val ALBUM = "OpenThinking Space Screenshots"
    const val NOTES_DIR = "OTSVoiceNotes"
    private const val NOTE_SUFFIX = "_Note"
    private const val MAX_NAME_TRIES = 500
    private const val MAX_SHARED_PX = 4096

    private val SHOT_NAME = Regex("""^Screenshot(\d+)\.png$""")

    // MediaStore renames on a name clash ("Screenshot1_Note (1).m4a"); still the same note.
    private val NOTE_NAME = Regex("""^(.+)$NOTE_SUFFIX(?: \(\d+\))?\.m4a$""")

    /** A saved screenshot and, if there is one, its pinned voice note. */
    data class Shot(
        val uri: Uri,
        val baseName: String,
        val noteUri: Uri?,
        val caption: String = "",
        val pinned: Boolean = false,
        /** Text read from the image by OCR. Empty until the screenshot has been scanned. */
        val ocrText: String = "",
        /** What the voice note says (empty until it has been transcribed). */
        val transcript: String = "",
    )

    private val imagesCollection: Uri
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val audioCollection: Uri
        get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val albumPath get() = "${Environment.DIRECTORY_PICTURES}/$ALBUM"

    // "Recordings" exists as a top-level audio directory from Android 12; before that, Music is
    // the closest allowed home.
    private val notesPath: String
        get() {
            val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Environment.DIRECTORY_RECORDINGS
            } else {
                Environment.DIRECTORY_MUSIC
            }
            return "$top/$NOTES_DIR"
        }

    /**
     * Saves [bitmap] as the next `Screenshot<N>.png` in the album. Returns the Uri and the base name
     * (`Screenshot<N>`) to use for the voice note, or null on failure.
     */
    fun saveScreenshot(context: Context, bitmap: Bitmap): Pair<Uri, String>? {
        val png = java.io.ByteArrayOutputStream().also {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG compression failed" }
        }.toByteArray()
        var n = nextNumber(context)
        repeat(MAX_NAME_TRIES) {
            val base = "Screenshot$n"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, albumPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = insert(context, imagesCollection, values) { it.write(png) } ?: return null
            if (displayName(context, uri) == "$base.png") return uri to base
            // A file with this name exists that we can't see (left by an earlier install), so
            // MediaStore renamed ours. Take the next number instead of keeping "Screenshot1 (2)".
            context.contentResolver.delete(uri, null, null)
            n++
        }
        return null
    }

    /** Saves a typed or shared note or link as a text card; the raw text becomes its caption. */
    fun saveText(context: Context, text: String): Uri? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val (uri, base) = saveScreenshot(context, CardRenderer.text(context, clean)) ?: return null
        MemoryMeta(context).apply {
            setCaption(base, clean)
            setOcr(base, clean) // the text is already known, so the gallery need not read the card
        }
        return uri
    }

    /** Saves a voice memo as a card with the recording pinned to it. The caller still owns (and should delete) [source]. */
    fun saveMemo(context: Context, source: File, durationMs: Long): Uri? {
        val (uri, base) = saveScreenshot(context, CardRenderer.memo(context, durationMs)) ?: return null
        MemoryMeta(context).setOcr(base, "")
        if (saveVoiceNote(context, source, base) == null) {
            context.contentResolver.delete(uri, null, null)
            MemoryMeta(context).forget(base)
            return null
        }
        return uri
    }

    /**
     * Saves audio shared from another app (a chat voice message, say) as a voice memo.
     * ponytail: stored under an .m4a name whatever the format; players and the decoder sniff the content.
     */
    fun saveAudio(context: Context, source: Uri): Uri? {
        val temp = File.createTempFile("shared-", ".m4a", context.cacheDir)
        try {
            context.contentResolver.openInputStream(source)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: return null
            val retriever = android.media.MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(temp.path)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: RuntimeException) {
                return null // not audio we can read
            } finally {
                retriever.release()
            }
            return saveMemo(context, temp, duration)
        } finally {
            temp.delete()
        }
    }

    /** Copies an image shared from another app into the album. Large photos are scaled down on decode. */
    fun saveImage(context: Context, source: Uri): Uri? {
        val bitmap = runCatching {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_SHARED_PX) decoder.setTargetSampleSize((longest + MAX_SHARED_PX - 1) / MAX_SHARED_PX)
            }
        }.onFailure { Timber.e(it, "Could not read shared image $source") }.getOrNull() ?: return null
        return saveScreenshot(context, bitmap)?.first.also { bitmap.recycle() }
    }

    /** Copies [source] (an .m4a) in as `<baseName>_Note.m4a`. Returns its Uri, or null on failure. */
    fun saveVoiceNote(context: Context, source: File, baseName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName$NOTE_SUFFIX.m4a")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, notesPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return insert(context, audioCollection, values) { out ->
            source.inputStream().use { it.copyTo(out) }
        }
    }

    /** Newest first. */
    fun listShots(context: Context): List<Shot> {
        val notes = notesByBaseName(context)
        val shots = mutableListOf<Shot>()
        context.contentResolver.query(
            imagesCollection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$albumPath%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val base = name.removeSuffix(".png")
                shots += Shot(ContentUris.withAppendedId(imagesCollection, c.getLong(0)), base, notes[base])
            }
        }
        return shots
    }

    /** The voice note pinned to the screenshot at [imageUri], if any. */
    fun findNote(context: Context, imageUri: Uri): Uri? {
        val name = displayName(context, imageUri) ?: return null
        return notesByBaseName(context)[name.removeSuffix(".png")]
    }

    fun displayName(context: Context, uri: Uri): String? = context.contentResolver.query(
        uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Deletes the screenshot and its note. */
    fun delete(context: Context, imageUri: Uri) {
        findNote(context, imageUri)?.let { context.contentResolver.delete(it, null, null) }
        context.contentResolver.delete(imageUri, null, null)
    }

    private fun notesByBaseName(context: Context): Map<String, Uri> {
        val map = HashMap<String, Uri>()
        context.contentResolver.query(
            audioCollection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("%/$NOTES_DIR%"),
            "${MediaStore.MediaColumns.DATE_ADDED} ASC, ${MediaStore.MediaColumns._ID} ASC", // newest wins
        )?.use { c ->
            while (c.moveToNext()) {
                val base = NOTE_NAME.find(c.getString(1) ?: continue)?.groupValues?.get(1) ?: continue
                map[base] = ContentUris.withAppendedId(audioCollection, c.getLong(0))
            }
        }
        return map
    }

    // ponytail: scans the album for the highest number. Files from a previous install are invisible
    // to a scoped-storage query (MediaStore then renames on collision); fine until that matters.
    private fun nextNumber(context: Context): Int {
        var max = 0
        context.contentResolver.query(
            imagesCollection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$albumPath%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                SHOT_NAME.find(c.getString(0) ?: continue)?.let { max = maxOf(max, it.groupValues[1].toInt()) }
            }
        }
        return max + 1
    }

    /** Inserts a pending row, streams the bytes in, then publishes it; rolls the row back on any failure. */
    private fun insert(
        context: Context,
        collection: Uri,
        values: ContentValues,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("No output stream for $uri")
            out.use(write)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            uri
        } catch (e: IOException) {
            Timber.e(e, "Failed to write $uri")
            resolver.delete(uri, null, null)
            null
        } catch (e: IllegalStateException) {
            Timber.e(e, "Failed to write $uri")
            resolver.delete(uri, null, null)
            null
        }
    }
}
