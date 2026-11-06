@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.openthinking.blankassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openthinking.blankassistant.ocr.TextReader
import com.openthinking.blankassistant.reminder.Reminders
import com.openthinking.blankassistant.storage.CaptureStore
import com.openthinking.blankassistant.storage.CaptureStore.Shot
import com.openthinking.blankassistant.storage.MemoryMeta
import com.openthinking.blankassistant.ui.OtsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Home screen: your memories (screenshots with optional voice notes and text notes) as a spacious
 * Material 3 grid in the wallpaper's colours. Search the text notes, filter by pinned / voice note,
 * long-press to select several. Tap to open [ViewerActivity].
 */
class GalleryActivity : ComponentActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = LruCache<Uri, ImageBitmap>(80)
    private val ocrIo = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private var shots by mutableStateOf<List<Shot>?>(null)
    private var isDefault by mutableStateOf(true)
    private var unscanned by mutableIntStateOf(0)
    private var noteOpen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        noteOpen = intent.getBooleanExtra(EXTRA_NEW_NOTE, false) || intent.getStringExtra(EXTRA_NEW_NOTE) == "true" // the "New note" launcher shortcut
        setContent {
            OtsTheme {
                val askRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    isDefault = isDefaultAssistant(this)
                }
                GalleryScreen(
                    shots = shots,
                    unscanned = unscanned,
                    noteOpen = noteOpen,
                    onNoteOpen = { noteOpen = it },
                    onNote = { text ->
                        io.execute {
                            val saved = CaptureStore.saveText(this, text)
                            runOnUiThread {
                                Toast.makeText(this, if (saved != null) R.string.msg_note_saved else R.string.msg_save_failed, Toast.LENGTH_SHORT).show()
                            }
                            reload()
                        }
                    },
                    onMemo = { startActivity(Intent(this, MemoActivity::class.java)) },
                    isDefault = isDefault,
                    loadThumb = ::loadThumb,
                    onOpen = { startActivity(Intent(this, ViewerActivity::class.java).setData(it.uri)) },
                    onDelete = { doomed ->
                        io.execute {
                            val meta = MemoryMeta(this)
                            doomed.forEach {
                                Reminders.cancel(this, it.uri, it.baseName)
                                meta.forget(it.baseName)
                                CaptureStore.delete(this, it.uri)
                            }
                            reload()
                        }
                    },
                    onPin = { chosen, pin ->
                        val meta = MemoryMeta(this)
                        chosen.forEach { meta.setPinned(it.baseName, pin) }
                        io.execute(::reload)
                    },
                    onSetDefault = { askRole.launch(assistantRoleIntent(this)) },
                    onSetup = { startActivity(Intent(this, MainActivity::class.java)) },
                )
            }
        }

        // First launch: ask once to become the default assistant (the system shows the confirmation).
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        if (!isDefaultAssistant(this) && !prefs.getBoolean("asked_default", false)) {
            prefs.edit().putBoolean("asked_default", true).apply()
            startActivity(assistantRoleIntent(this))
        }
    }

    // Reload every time: the viewer can delete or edit entries (edits overwrite in place).
    override fun onResume() {
        super.onResume()
        isDefault = isDefaultAssistant(this)
        thumbs.evictAll()
        io.execute(::reload)
    }

    private fun reload() {
        val meta = MemoryMeta(this)
        val list = CaptureStore.listShots(this)
            .map {
                it.copy(
                    caption = meta.caption(it.baseName),
                    pinned = meta.pinned(it.baseName),
                    ocrText = meta.ocr(it.baseName).orEmpty(),
                )
            }
            .sortedByDescending { it.pinned } // stable: newest first inside each group
        runOnUiThread { if (!isDestroyed) shots = list }
        scanText(list)
    }

    /**
     * Reads the text of every screenshot that has not been scanned yet, newest first, one at a time.
     * Results land in [MemoryMeta] so search can use them. Skipped if a scan is already running;
     * the next reload picks up whatever is left.
     */
    private fun scanText(list: List<Shot>) {
        val meta = MemoryMeta(this)
        val todo = list.filter { meta.ocr(it.baseName) == null }
        if (todo.isEmpty() || !scanning.compareAndSet(false, true)) return
        runOnUiThread { unscanned = todo.size }
        ocrIo.execute {
            try {
                TextReader(this).use { reader ->
                    todo.forEachIndexed { i, shot ->
                        if (isDestroyed) return@use
                        // A screenshot we could not decode stays unscanned and is retried next time.
                        TextReader.decode(this, shot.uri)?.let { bitmap ->
                            val text = reader.read(bitmap)
                            bitmap.recycle()
                            meta.setOcr(shot.baseName, text)
                            runOnUiThread {
                                shots = shots?.map { if (it.baseName == shot.baseName) it.copy(ocrText = text) else it }
                            }
                        }
                        runOnUiThread { unscanned = todo.size - i - 1 }
                    }
                }
            } finally {
                scanning.set(false)
                runOnUiThread { unscanned = 0 }
            }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        ocrIo.shutdownNow()
        super.onDestroy()
    }

    private suspend fun loadThumb(uri: Uri): ImageBitmap? = thumbs.get(uri) ?: withContext(Dispatchers.IO) {
        runCatching { contentResolver.loadThumbnail(uri, Size(540, 720), null).asImageBitmap() }
            .getOrNull()?.also { thumbs.put(uri, it) }
    }
}

private const val EXTRA_NEW_NOTE = "new_note"

private enum class Filter(val label: String, val icon: ImageVector) {
    All("All", Icons.Rounded.PhotoLibrary), Pinned("Pinned", Icons.Rounded.Star), Voice("Voice notes", Icons.Rounded.Mic)
}

@Composable
private fun GalleryScreen(
    shots: List<Shot>?,
    unscanned: Int,
    noteOpen: Boolean,
    onNoteOpen: (Boolean) -> Unit,
    onNote: (String) -> Unit,
    onMemo: () -> Unit,
    isDefault: Boolean,
    loadThumb: suspend (Uri) -> ImageBitmap?,
    onOpen: (Shot) -> Unit,
    onDelete: (List<Shot>) -> Unit,
    onPin: (List<Shot>, Boolean) -> Unit,
    onSetDefault: () -> Unit,
    onSetup: () -> Unit,
) {
    // Long-press a card to start selecting; while anything is selected, taps toggle cards.
    var selected by remember { mutableStateOf(emptySet<Uri>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.All) }
    val selecting = selected.isNotEmpty()
    val chosen = shots.orEmpty().filter { it.uri in selected }
    BackHandler(enabled = selecting) { selected = emptySet() }

    val visible = shots.orEmpty().filter {
        (filter == Filter.All || (filter == Filter.Pinned && it.pinned) || (filter == Filter.Voice && it.noteUri != null)) &&
            (query.isBlank() ||
                it.caption.contains(query.trim(), ignoreCase = true) ||
                it.ocrText.contains(query.trim(), ignoreCase = true))
    }

    // A soft wash of the theme's colour at the top that settles into the plain surface lower down.
    val scheme = MaterialTheme.colorScheme
    val wash = Brush.verticalGradient(0f to scheme.primaryContainer.copy(alpha = 0.65f), 0.5f to scheme.surface, 1f to scheme.surface)
    Scaffold(
        modifier = Modifier.background(wash),
        containerColor = Color.Transparent,
        floatingActionButton = {
            AnimatedVisibility(visible = !selecting, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(onClick = onMemo) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Record a voice memo")
                    }
                    ExtendedFloatingActionButton(onClick = { onNoteOpen(true) }) {
                        Icon(Icons.Rounded.Edit, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("New note")
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Crossfade(if (selecting) "${chosen.size} selected" else "Thinking Space", label = "title") {
                        Text(it, style = MaterialTheme.typography.titleLargeEmphasized)
                    }
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        val allPinned = chosen.all { it.pinned }
                        IconButton(onClick = { onPin(chosen, !allPinned); selected = emptySet() }) {
                            Icon(
                                if (allPinned) Icons.Rounded.StarBorder else Icons.Rounded.Star,
                                contentDescription = if (allPinned) "Unpin selected" else "Pin selected",
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete selected")
                        }
                    } else {
                        FilledTonalIconButton(onClick = onSetup) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        when {
            shots == null -> Unit
            shots.isEmpty() -> EmptyState(isDefault, onSetDefault, onSetup, Modifier.padding(padding))
            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                // The title bar stays put: content scrolls inside the area below it, never under it.
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = padding.calculateBottomPadding() + 120.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalItemSpacing = 14.dp,
            ) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!selecting) {
                            Hero(
                                total = shots.size,
                                pinned = shots.count { it.pinned },
                                voice = shots.count { it.noteUri != null },
                            )
                        }
                        if (!isDefault) DefaultBanner(onSetDefault)
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search notes and text in screenshots") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = scheme.surfaceContainerHigh,
                                unfocusedContainerColor = scheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                        // Connected button group: the selected option morphs into a pill.
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        ) {
                            Filter.entries.forEachIndexed { i, option ->
                                ToggleButton(
                                    checked = filter == option,
                                    onCheckedChange = { filter = option },
                                    shapes = when (i) {
                                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                        Filter.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                    },
                                ) {
                                    Icon(option.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(option.label)
                                }
                            }
                        }
                        // Keeps the last count while the row slides away, so it never says "0 screenshots".
                        var lastCount by remember { mutableIntStateOf(1) }
                        LaunchedEffect(unscanned) { if (unscanned > 0) lastCount = unscanned }
                        AnimatedVisibility(unscanned > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                LoadingIndicator(Modifier.size(36.dp))
                                Text(
                                    "Reading text in $lastCount screenshot${if (lastCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            "Nothing matches.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(visible, key = { _, shot -> shot.uri.toString() }) { index, shot ->
                    val isSelected = shot.uri in selected
                    fun toggle() { selected = if (isSelected) selected - shot.uri else selected + shot.uri }
                    ShotCard(
                        shot, loadThumb,
                        index = index,
                        selecting = selecting,
                        selected = isSelected,
                        onClick = { if (selecting) toggle() else onOpen(shot) },
                        onLongClick = ::toggle,
                        modifier = Modifier.animateItem(), // cards glide when one is deleted, pinned or filtered out
                    )
                }
            }
        }
    }

    if (noteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onNoteOpen(false) },
            title = { Text("New note") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Write or paste anything") },
                    minLines = 4,
                    maxLines = 10,
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { onNote(text); onNoteOpen(false) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { onNoteOpen(false) }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (chosen.size == 1) "Delete screenshot?" else "Delete ${chosen.size} screenshots?") },
            text = { Text(if (chosen.any { it.noteUri != null }) "Their voice notes are deleted too." else "This can\u2019t be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    selected = emptySet()
                    onDelete(chosen)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The big colourful header: the memory count as a huge number on a primary-to-tertiary wash, with two
 * soft expressive shapes behind it and small stat pills. Both colours sit at the same tone, so the
 * "on primary" text is readable across the whole gradient.
 */
@Composable
private fun Hero(total: Int, pinned: Int, voice: Int) {
    // Counts up from 0 on first show, and ticks to the new number when it changes.
    var target by remember { mutableIntStateOf(0) }
    LaunchedEffect(total) { target = total }
    val shown by animateIntAsState(target, tween(800, easing = FastOutSlowInEasing), label = "count")
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(40.dp))
            .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 70.dp, y = (-70).dp)
                .size(240.dp)
                .clip(MaterialShapes.Cookie12Sided.toShape())
                .background(scheme.onPrimary.copy(alpha = 0.12f)),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-28).dp, y = 44.dp)
                .size(130.dp)
                .clip(MaterialShapes.Flower.toShape())
                .background(scheme.onTertiary.copy(alpha = 0.16f)),
        )
        Column(Modifier.padding(horizontal = 26.dp, vertical = 24.dp)) {
            Text(
                if (total == 1) "Memory" else "Memories",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = scheme.onPrimary.copy(alpha = 0.85f),
            )
            Text(
                "$shown",
                style = MaterialTheme.typography.displayLargeEmphasized,
                color = scheme.onPrimary,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(Icons.Rounded.Star, "$pinned pinned")
                StatPill(Icons.Rounded.Mic, "$voice with voice")
            }
        }
    }
}

@Composable
private fun StatPill(icon: ImageVector, label: String) {
    val on = MaterialTheme.colorScheme.onPrimary
    Row(
        Modifier
            .clip(CircleShape)
            .background(on.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = on, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = on)
    }
}

@Composable
private fun DefaultBanner(onSetDefault: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLargeIncreased)
            .background(Brush.linearGradient(listOf(scheme.tertiaryContainer, scheme.secondaryContainer))),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 36.dp, y = (-36).dp)
                .size(120.dp)
                .clip(MaterialShapes.Burst.toShape())
                .background(scheme.onTertiaryContainer.copy(alpha = 0.10f)),
        )
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Make this your default assistant",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = scheme.onTertiaryContainer,
            )
            Text(
                "Then holding the power button captures the screen into Thinking Space.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onTertiaryContainer,
            )
            Button(onClick = onSetDefault, shapes = ButtonDefaults.shapes()) { Text("Set as default") }
        }
    }
}

// Cards cycle through three colour families, three heights and a few corner shapes, so the wall of
// screenshots reads as a lively mosaic instead of a spreadsheet.
private val cardRatios = floatArrayOf(0.72f, 0.92f, 0.82f, 0.66f)
private val cardShapes = listOf(
    RoundedCornerShape(36.dp),
    RoundedCornerShape(topStart = 44.dp, topEnd = 14.dp, bottomEnd = 44.dp, bottomStart = 14.dp),
    RoundedCornerShape(topStart = 14.dp, topEnd = 44.dp, bottomEnd = 14.dp, bottomStart = 44.dp),
    RoundedCornerShape(28.dp),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotCard(
    shot: Shot,
    loadThumb: suspend (Uri) -> ImageBitmap?,
    index: Int,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumb by produceState<ImageBitmap?>(null, shot.uri) { value = loadThumb(shot.uri) }
    // The first cards rise in one after another. Later ones (scrolled into view) just appear.
    val appear = remember { Animatable(if (index < 10) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (appear.value < 1f) {
            delay(index * 50L)
            appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
        }
    }
    val thumbAlpha by animateFloatAsState(if (thumb != null) 1f else 0f, tween(300), label = "thumb")
    val shade by animateFloatAsState(if (selected) 0.25f else 0f, label = "shade")
    val scale by animateFloatAsState(if (selected) 0.92f else 1f, label = "select")
    val scheme = MaterialTheme.colorScheme
    val (frame, onFrame) = when (index % 3) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Card(
        shape = cardShapes[index % cardShapes.size],
        colors = CardDefaults.cardColors(containerColor = frame, contentColor = onFrame),
        border = if (selected) BorderStroke(3.dp, scheme.primary) else null,
        modifier = modifier
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * 56.dp.toPx()
            }
            .scale(scale)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(cardRatios[index % cardRatios.size])
                    .clip(RoundedCornerShape(28.dp))
                    .background(scheme.surfaceContainerHighest),
            ) {
                thumb?.let {
                    Image(
                        bitmap = it,
                        contentDescription = if (shot.noteUri != null) "Screenshot with a voice note" else "Screenshot",
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = thumbAlpha },
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                    )
                }
                if (shade > 0f) Box(Modifier.fillMaxSize().background(scheme.primary.copy(alpha = shade)))
                AnimatedVisibility(
                    visible = selecting,
                    modifier = Modifier.align(Alignment.TopEnd),
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (selected) "Selected" else "Not selected",
                        tint = if (selected) scheme.primary else Color.White,
                        modifier = Modifier
                            .padding(12.dp)
                            .size(26.dp)
                            .then(if (selected) Modifier.background(Color.White, CircleShape) else Modifier),
                    )
                }
                if (shot.pinned) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = "Pinned",
                        tint = scheme.onTertiary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(MaterialShapes.Cookie6Sided.toShape())
                            .background(scheme.tertiary)
                            .padding(7.dp)
                            .size(16.dp),
                    )
                }
                if (shot.noteUri != null) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = "Has a voice note",
                        tint = scheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .clip(MaterialShapes.Cookie9Sided.toShape())
                            .background(scheme.primary)
                            .padding(9.dp)
                            .size(18.dp),
                    )
                }
            }
            if (shot.caption.isNotBlank()) {
                Text(
                    shot.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(isDefault: Boolean, onSetDefault: () -> Unit, onSetup: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(190.dp)
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 18.dp, y = (-6).dp)
                    .size(64.dp)
                    .clip(MaterialShapes.Flower.toShape())
                    .background(scheme.secondaryContainer),
            )
            Icon(
                Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("No memories yet", style = MaterialTheme.typography.headlineMediumEmphasized)
        Spacer(Modifier.height(8.dp))
        Text(
            "Hold the power button on any screen, add a voice note if you like, then tap Save.",
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (isDefault) {
            FilledTonalButton(onClick = onSetup, shapes = ButtonDefaults.shapes()) { Text("Open settings") }
        } else {
            Button(onClick = onSetDefault, shapes = ButtonDefaults.shapes()) { Text("Set as default assistant") }
        }
    }
}
