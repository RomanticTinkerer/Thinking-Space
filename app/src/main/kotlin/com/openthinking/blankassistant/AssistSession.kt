package com.openthinking.blankassistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.openthinking.blankassistant.audio.RecordingService
import com.openthinking.blankassistant.audio.VoiceNoteRecorder
import com.openthinking.blankassistant.events.CalendarEvent
import com.openthinking.blankassistant.events.EventParser
import com.openthinking.blankassistant.events.toIntent
import com.openthinking.blankassistant.ocr.TextReader
import com.openthinking.blankassistant.reminder.Reminders
import com.openthinking.blankassistant.storage.CaptureStore
import com.openthinking.blankassistant.storage.MemoryMeta
import com.openthinking.blankassistant.ui.CaptureOverlayView
import com.openthinking.blankassistant.ui.CaptureOverlayView.NoteState
import com.openthinking.blankassistant.ui.CaptureOverlayView.Remind
import com.openthinking.blankassistant.ui.CaptureOverlayView.TextState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * One assistant invocation (hold power button, etc.).
 *
 * The system hands us a screenshot of whatever was on screen via [onHandleScreenshot]; we show it in
 * [CaptureOverlayView] and let the user Edit it, attach a voice note, or just Save it. Small options
 * next to those: pin, remind later, share, copy the text in it, or add an event it mentions to the calendar.
 *
 * Nothing is written to shared storage until the user chooses Edit or Save; dismissing discards
 * everything, including any in-progress recording.
 */
class AssistSession(sessionContext: Context) :
    VoiceInteractionSession(sessionContext),
    CaptureOverlayView.Listener,
    VoiceNoteRecorder.Callbacks {

    private enum class Pending { SAVE, EDIT, SHARE, CALENDAR }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlay by lazy { CaptureOverlayView(context, this) }

    private var screenshot: Bitmap? = null
    // Set once the screenshot has been written; the voice note is named after it.
    private var savedImageUri: Uri? = null
    private var savedBaseName: String? = null

    private var recorder: VoiceNoteRecorder? = null
    private var noteFile: File? = null
    private var noteDurationMs = 0L

    /** What to do as soon as an in-flight recording has been finalised (the user tapped Save/Edit mid-recording). */
    private var pending: Pending? = null
    private var busy = false
    private var screenshotTimeout: Job? = null

    // The option chips. All of it is applied to the saved screenshot, so nothing is stored if the user discards.
    private var pinned = false
    private var remind = Remind.OFF
    private var ocrJob: Job? = null
    private var ocrText: String? = null // null while still being read
    private var event: CalendarEvent? = null

    override fun onCreateContentView(): View = overlay

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // The default session window is a wrap_content bottom panel; the zoom-out animation needs the
        // whole screen, drawn edge to edge over a transparent window.
        window.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDecorFitsSystemWindows(false)
        }

        resetState()
        overlay.prepare()
        if (showFlags and SHOW_WITH_SCREENSHOT == 0) {
            // The system will not send a screenshot; don't wait for one.
            overlay.showUnavailable()
        }

        // With "Use screenshot" switched off the system may never call onHandleScreenshot at all,
        // so don't leave the user looking at a dimmed screen: explain after a short wait. A late
        // screenshot still replaces the message.
        screenshotTimeout?.cancel()
        screenshotTimeout = uiScope.launch {
            delay(SCREENSHOT_TIMEOUT_MS)
            if (screenshot == null) overlay.showUnavailable()
        }
    }

    /** Null when "Use screenshot" is off, or the foreground window is FLAG_SECURE. */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot == null) {
            overlay.showUnavailable()
            return
        }
        this.screenshot = screenshot
        overlay.showScreenshot(screenshot)
        readText(screenshot)
    }

    /** Reads the text in the capture in the background, once the opening animation is done (it would make it stutter). */
    private fun readText(shot: Bitmap) {
        ocrJob?.cancel()
        ocrJob = uiScope.launch {
            delay(OCR_DELAY_MS)
            val text = withContext(Dispatchers.Default) {
                // The system may hand over a hardware bitmap, whose pixels cannot be read.
                val readable = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
                readable?.let { TextReader(context).use { reader -> reader.read(it) } }
            }
            if (text == null) { // could not even read the pixels; stays unknown, so the gallery scans it later
                overlay.setTextState(TextState.NONE)
                return@launch
            }
            ocrText = text
            // ponytail: with several events the first upcoming one is offered; the viewer lists them all.
            val found = EventParser.parse(text)
            event = found.firstOrNull { it.end >= System.currentTimeMillis() } ?: found.firstOrNull()
            overlay.setTextState(if (text.isBlank()) TextState.NONE else TextState.READY)
            overlay.setEventFound(event != null)
        }
    }

    override fun onHide() {
        super.onHide()
        releaseCapture()
    }

    override fun onDestroy() {
        releaseCapture()
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        finish()
    }

    // --- CaptureOverlayView.Listener ---------------------------------------------------------

    override fun onDismiss() = finish()

    override fun onRecordToggle() {
        val active = recorder
        if (active != null) {
            // Tap while recording = stop. Taps during the brief startup (service coming up) are ignored.
            if (active.isActive) active.stop()
            return
        }
        startRecording()
    }

    /** Reset: throw this take away and start a fresh one. */
    override fun onRecordReset() {
        recorder?.discard()
        recorder = null
        RecordingService.stop(context)
        startRecording()
    }

    override fun onSave() = completeWith(Pending.SAVE)

    override fun onPin(pinned: Boolean) {
        this.pinned = pinned
    }

    override fun onRemind(option: Remind) {
        remind = option
        // An assistant overlay can't ask for permissions, so say so instead of setting a reminder that never shows.
        if (option != Remind.OFF && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            toast(R.string.msg_need_notifications)
        }
    }

    override fun onShare() = completeWith(Pending.SHARE)

    override fun onCalendar() = completeWith(Pending.CALENDAR)

    override fun onCopyText() {
        val text = ocrText?.takeIf { it.isNotBlank() } ?: return
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Screenshot text", text))
        toast(R.string.msg_text_copied)
    }

    override fun onEdit() = completeWith(Pending.EDIT)

    // --- VoiceNoteRecorder.Callbacks ---------------------------------------------------------

    override fun onProgress(durationMs: Long, amplitude: Int) {
        overlay.setRecordingProgress(durationMs, amplitude)
    }

    override fun onFinished(file: File, durationMs: Long) {
        recorder = null
        RecordingService.stop(context)
        noteFile = file
        noteDurationMs = durationMs
        overlay.setNoteState(NoteState.RECORDED, durationMs)
        runPending()
    }

    override fun onFailed() {
        recorder = null
        RecordingService.stop(context)
        overlay.setNoteState(NoteState.NONE)
        toast(R.string.msg_record_failed)
        // If the user had already asked to save, still save the screenshot without a note.
        runPending()
    }

    // --- Recording ---------------------------------------------------------------------------

    private fun startRecording() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // An assistant overlay can't show a permission dialog; send the user to the setup screen.
            toast(R.string.msg_need_mic)
            startActivityFromSession(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_REQUEST_MIC, true),
            )
            finish()
            return
        }
        // Re-recording replaces the previous note.
        noteFile?.delete()
        noteFile = null

        val newRecorder = VoiceNoteRecorder(context, this)
        recorder = newRecorder
        overlay.setNoteState(NoteState.RECORDING, 0L)
        // Mic access needs the microphone foreground service up first; it calls back when ready.
        // Success or failure of the recording itself is reported through the callbacks.
        RecordingService.start(context) {
            if (recorder === newRecorder) newRecorder.start() else RecordingService.stop(context)
        }
    }

    // --- Save / Edit -------------------------------------------------------------------------

    private fun completeWith(action: Pending) {
        if (busy || screenshot == null) return
        busy = true
        overlay.setBusy(true)
        pending = action

        val active = recorder
        if (active != null && active.isActive) {
            // Finish the note first; onFinished/onFailed resumes via runPending().
            active.stop()
        } else {
            runPending()
        }
    }

    private fun runPending() {
        val action = pending ?: return
        pending = null
        val bitmap = screenshot ?: return
        val note = noteFile

        uiScope.launch {
            val (imageUri, noteSaved) = withContext(Dispatchers.IO) {
                if (savedImageUri == null) {
                    CaptureStore.saveScreenshot(context, bitmap)?.let { (uri, base) ->
                        savedImageUri = uri
                        savedBaseName = base
                    }
                }
                val base = savedBaseName
                if (savedImageUri != null && base != null) applyOptions(savedImageUri!!, base)
                val noteUri = if (savedImageUri != null && base != null && note != null) {
                    CaptureStore.saveVoiceNote(context, note, base)
                } else {
                    null
                }
                savedImageUri to (noteUri != null)
            }

            if (imageUri == null) {
                toast(R.string.msg_save_failed)
                busy = false
                overlay.setBusy(false)
                return@launch
            }
            when (action) {
                Pending.SAVE -> toast(if (noteSaved) R.string.msg_saved_with_note else R.string.msg_saved)
                Pending.EDIT -> openInEditor(imageUri)
                Pending.SHARE -> share(imageUri)
                Pending.CALENDAR -> event?.let { openCalendar(it) }
            }
            finish()
        }
    }

    /** Pin, reminder and the text that was already read go onto the saved screenshot. */
    private fun applyOptions(uri: Uri, base: String) {
        val meta = MemoryMeta(context)
        if (pinned) meta.setPinned(base, true)
        // Saves the gallery from reading the same screenshot again. Still null if the user was quicker than the reader.
        ocrText?.let { meta.setOcr(base, it) }
        val at = when (remind) {
            Remind.OFF -> 0L
            Remind.HOUR -> Reminders.hoursFromNow(1)
            Remind.EVENING -> Reminders.nextAt(20)
            Remind.TOMORROW -> Reminders.nextAt(9, daysAhead = 1)
        }
        if (at > 0L) Reminders.schedule(context, uri, base, at)
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityFromSession(Intent.createChooser(send, null))
    }

    private fun openCalendar(event: CalendarEvent) {
        try {
            startAssistantActivity(event.toIntent())
        } catch (e: Exception) {
            Timber.w(e, "No calendar app")
            toast(R.string.msg_no_calendar)
        }
    }

    /**
     * Opens the built-in editor (vendored ReFra markup + crop, module `:editor`) on the saved
     * screenshot. It overwrites the same MediaStore entry when the user saves.
     */
    private fun openInEditor(uri: Uri) {
        val intent = Intent()
            .setClassName(context.packageName, EDITOR_ACTIVITY)
            .setData(uri)
        try {
            startActivityFromSession(intent)
        } catch (e: Exception) {
            Timber.e(e, "Could not open the editor")
            toast(R.string.msg_no_editor)
        }
    }

    // --- Helpers -----------------------------------------------------------------------------

    /**
     * Sessions must start activities through [startAssistantActivity]: it launches into the
     * assistant task on the system's behalf. A plain startActivity() from a session context is
     * treated as a background launch and can be silently dropped.
     */
    private fun startActivityFromSession(intent: Intent) {
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "startAssistantActivity failed; falling back to startActivity")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun resetState() {
        releaseCapture()
        screenshot = null
        savedImageUri = null
        savedBaseName = null
        pending = null
        busy = false
        ocrText = null
        event = null
        pinned = false
        remind = Remind.OFF
    }

    /** Stops any recording and deletes the temporary note; safe to call repeatedly. */
    private fun releaseCapture() {
        ocrJob?.cancel()
        ocrJob = null
        recorder?.discard()
        recorder = null
        RecordingService.stop(context)
        noteFile?.delete()
        noteFile = null
        noteDurationMs = 0L
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SCREENSHOT_TIMEOUT_MS = 1_500L
        const val OCR_DELAY_MS = 1_200L
        const val EDITOR_ACTIVITY = "com.openthinking.blankassistant.editor.EditorActivity"
    }
}
