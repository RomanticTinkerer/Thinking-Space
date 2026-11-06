@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.openthinking.blankassistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.openthinking.blankassistant.audio.NotePlayer
import com.openthinking.blankassistant.events.CalendarEvent
import com.openthinking.blankassistant.events.EventParser
import com.openthinking.blankassistant.events.toIntent
import com.openthinking.blankassistant.ocr.TextReader
import com.openthinking.blankassistant.reminder.Reminders
import com.openthinking.blankassistant.storage.CaptureStore
import com.openthinking.blankassistant.storage.MemoryMeta
import com.openthinking.blankassistant.ui.OtsTheme
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * One memory, full size: the screenshot, a text note, a reminder, and the pinned voice note.
 * Pin, Share, Edit and Delete live in the top bar.
 */
class ViewerActivity : ComponentActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ocrIo = Executors.newSingleThreadExecutor() // OCR takes seconds; keep it off [io]
    private lateinit var uri: Uri
    private lateinit var player: NotePlayer
    private lateinit var meta: MemoryMeta
    private var base = ""
    private var image by mutableStateOf<ImageBitmap?>(null)
    private var note by mutableStateOf<Uri?>(null)
    private var caption by mutableStateOf("")
    private var pinned by mutableStateOf(false)
    private var remindAt by mutableLongStateOf(0L)
    private var ocrText by mutableStateOf<String?>(null) // null = not scanned yet
    private var ocrBusy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: return finish()
        player = NotePlayer(this)
        meta = MemoryMeta(this)
        enableEdgeToEdge()
        setContent {
            OtsTheme {
                ViewerScreen(
                    image = image,
                    hasNote = note != null,
                    player = player,
                    caption = caption,
                    pinned = pinned,
                    remindAt = remindAt,
                    ocrText = ocrText,
                    ocrBusy = ocrBusy,
                    onReadText = ::readText,
                    onBack = ::finish,
                    onCaption = {
                        caption = it
                        if (base.isNotEmpty()) meta.setCaption(base, it)
                    },
                    onPin = {
                        pinned = !pinned
                        if (base.isNotEmpty()) meta.setPinned(base, pinned)
                    },
                    onShare = ::share,
                    onRemind = ::remind,
                    // Normal activity here, so a plain startActivity is fine (unlike from the assistant session).
                    onEdit = { startActivity(Intent().setClassName(packageName, EDITOR_ACTIVITY).setData(uri)) },
                    onDelete = {
                        player.release()
                        io.execute {
                            if (base.isNotEmpty()) {
                                Reminders.cancel(this, uri, base)
                                meta.forget(base)
                            }
                            CaptureStore.delete(this, uri)
                            runOnUiThread { finish() }
                        }
                    },
                )
            }
        }
    }

    // Reload on every resume: the editor overwrites the file in place.
    override fun onResume() {
        super.onResume()
        io.execute {
            val bitmap = decode()
            val found = CaptureStore.findNote(this, uri)
            val name = CaptureStore.displayName(this, uri)?.removeSuffix(".png").orEmpty()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                base = name
                image = bitmap?.asImageBitmap()
                note = found
                caption = meta.caption(name)
                pinned = meta.pinned(name)
                remindAt = meta.reminderAt(name).takeIf { it > System.currentTimeMillis() } ?: 0L
                ocrText = meta.ocr(name)
                if (found != player.loaded) player.load(found)
            }
        }
    }

    override fun onStop() {
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        io.shutdownNow()
        ocrIo.shutdownNow()
        super.onDestroy()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri("", uri) // lets the chooser pass the read grant on
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, null))
    }

    /**
     * Runs OCR on the full-size image. Skipped if the text is already known, unless [force]
     * (the editor changes the image in place, so the saved text can go stale; "Read again" fixes that).
     */
    private fun readText(force: Boolean) {
        if (ocrBusy || base.isEmpty() || (!force && ocrText != null)) return
        ocrBusy = true
        val name = base
        ocrIo.execute {
            val text = TextReader.decode(this, uri)?.let { bitmap ->
                TextReader(this).use { it.read(bitmap) }.also { bitmap.recycle() }
            }
            if (text != null) meta.setOcr(name, text)
            runOnUiThread {
                ocrBusy = false
                if (text != null) ocrText = text
            }
        }
    }

    /** [at] = epoch millis, or 0 to clear. */
    private fun remind(at: Long) {
        if (base.isEmpty()) return
        if (at == 0L) {
            Reminders.cancel(this, uri, base)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
            Reminders.schedule(this, uri, base, at)
        }
        remindAt = at
    }

    /** Decodes at most ~2048 px on the long edge; plenty for a phone screen. */
    private fun decode() = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_DIMENSION) decoder.setTargetSampleSize((longest + MAX_DIMENSION - 1) / MAX_DIMENSION)
        }
    }.onFailure { Timber.e(it, "Could not decode $uri") }.getOrNull()

    private companion object {
        const val MAX_DIMENSION = 2048
        const val EDITOR_ACTIVITY = "com.openthinking.blankassistant.editor.EditorActivity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(
    image: ImageBitmap?,
    hasNote: Boolean,
    player: NotePlayer,
    caption: String,
    pinned: Boolean,
    remindAt: Long,
    ocrText: String?,
    ocrBusy: Boolean,
    onReadText: (force: Boolean) -> Unit,
    onBack: () -> Unit,
    onCaption: (String) -> Unit,
    onPin: () -> Unit,
    onShare: () -> Unit,
    onRemind: (Long) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(
                    PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 20.dp,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLargeIncreased,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                image?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Screenshot",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            OutlinedTextField(
                value = caption,
                onValueChange = onCaption,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Add a note") },
                maxLines = 3,
                shape = MaterialTheme.shapes.extraLarge,
            )
            val events = remember(ocrText) { ocrText?.let(EventParser::parse).orEmpty() }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReminderChip(remindAt, onRemind)
                CalendarChip(events)
            }
            if (hasNote) NoteCard(player)
            // Floating toolbar: the main actions, with Edit as the lifted primary one.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    floatingActionButton = {
                        FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onEdit) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                        }
                    },
                ) {
                    IconButton(onClick = onPin) {
                        Icon(
                            if (pinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = if (pinned) "Unpin" else "Pin",
                        )
                    }
                    IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, contentDescription = "Share") }
                    IconButton(onClick = { showText = true; onReadText(false) }) {
                        Icon(Icons.Rounded.TextFields, contentDescription = "Text in image")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
                }
            }
        }
    }

    if (showText) TextSheet(ocrText, ocrBusy, onReadAgain = { onReadText(true) }, onDismiss = { showText = false })

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete screenshot?") },
            text = { Text(if (hasNote) "This also deletes its voice note." else "This can\u2019t be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSheet(text: String?, busy: Boolean, onReadAgain: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Text in this screenshot", style = MaterialTheme.typography.titleLargeEmphasized)
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Text("Reading\u2026")
                }
                text == null -> Text("Couldn\u2019t read this image.")
                text.isBlank() -> Text("No text found.")
                else -> SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!text.isNullOrBlank()) {
                    FilledTonalButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Screenshot text", text))
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Copy", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                TextButton(onClick = onReadAgain, enabled = !busy) { Text("Read again") }
            }
        }
    }
}

/** Shown when the text in the screenshot holds an event. One event opens the calendar straight away, several ask which. */
@Composable
private fun CalendarChip(events: List<CalendarEvent>) {
    if (events.isEmpty()) return
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    fun add(e: CalendarEvent) {
        try {
            context.startActivity(e.toIntent())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
        }
    }
    Box {
        AssistChip(
            onClick = { if (events.size == 1) add(events[0]) else open = true },
            leadingIcon = { Icon(Icons.Rounded.Event, contentDescription = null) },
            label = { Text("Add to calendar") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            events.forEach { e ->
                // All-day events are stored as UTC midnights, so they are shown in UTC too.
                val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or
                    if (e.allDay) DateUtils.FORMAT_UTC else DateUtils.FORMAT_SHOW_TIME
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(e.title)
                            Text(
                                DateUtils.formatDateTime(context, e.start, flags),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { open = false; add(e) },
                )
            }
        }
    }
}

@Composable
private fun ReminderChip(remindAt: Long, onRemind: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row {
        AssistChip(
            onClick = { open = true },
            leadingIcon = { Icon(Icons.Rounded.Alarm, contentDescription = null) },
            label = {
                Text(
                    if (remindAt > 0L) {
                        "Reminder " + DateUtils.formatDateTime(
                            androidx.compose.ui.platform.LocalContext.current, remindAt,
                            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
                        )
                    } else {
                        "Remind me"
                    },
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                "In 1 hour" to hoursFromNow(1),
                "This evening, 8 PM" to nextAt(20),
                "Tomorrow, 9 AM" to nextAt(9, daysAhead = 1),
            ).forEach { (label, at) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onRemind(at) })
            }
            if (remindAt > 0L) {
                DropdownMenuItem(text = { Text("Clear reminder") }, onClick = { open = false; onRemind(0L) })
            }
        }
    }
}

private fun hoursFromNow(h: Int) = System.currentTimeMillis() + h * DateUtils.HOUR_IN_MILLIS

/** Next time the clock hits [hour]:00, at least [daysAhead] days out (today's slot is skipped once it has passed). */
private fun nextAt(hour: Int, daysAhead: Int = 0): Long {
    val c = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, daysAhead)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
}

@Composable
private fun NoteCard(player: NotePlayer) {
    // Keep the slider/time moving while playing.
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.refresh()
            delay(100)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLargeIncreased,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilledIconButton(onClick = player::toggle) {
                Icon(
                    if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (player.playing) "Pause voice note" else "Play voice note",
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Voice note", style = MaterialTheme.typography.titleSmallEmphasized)
                Slider(
                    value = player.position.toFloat(),
                    onValueChange = { player.seekTo(it.toInt()) },
                    valueRange = 0f..max(player.duration, 1).toFloat(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(format(player.position), style = MaterialTheme.typography.labelMedium)
                    Text(format(player.duration), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun format(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
