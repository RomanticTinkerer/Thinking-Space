@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.openthinking.blankassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OtsTheme {
                val askRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    isDefault = isDefaultAssistant(this)
                }
                GalleryScreen(
                    shots = shots,
                    unscanned = unscanned,
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

private enum class Filter(val label: String) { All("All"), Pinned("Pinned"), Voice("Voice notes") }

@Composable
private fun GalleryScreen(
    shots: List<Shot>?,
    unscanned: Int,
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
    var searching by remember { mutableStateOf(false) }
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

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (selecting) "${chosen.size} selected" else "OpenThinking Space") },
                subtitle = {
                    if (!selecting && !shots.isNullOrEmpty()) {
                        Text(if (shots.size == 1) "1 memory" else "${shots.size} memories")
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
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search notes")
                        }
                        FilledTonalIconButton(onClick = onSetup) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        when {
            shots == null -> Unit
            shots.isEmpty() -> EmptyState(isDefault, onSetDefault, onSetup, Modifier.padding(padding))
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!isDefault) DefaultBanner(onSetDefault)
                        if (searching) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search notes and text in screenshots") },
                                singleLine = true,
                                shape = CircleShape,
                            )
                        }
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
                                ) { Text(option.label) }
                            }
                        }
                        if (unscanned > 0) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                LoadingIndicator(Modifier.size(36.dp))
                                Text(
                                    "Reading text in $unscanned screenshot${if (unscanned == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                items(visible, key = { it.uri.toString() }) { shot ->
                    val isSelected = shot.uri in selected
                    fun toggle() { selected = if (isSelected) selected - shot.uri else selected + shot.uri }
                    ShotCard(
                        shot, loadThumb,
                        selecting = selecting,
                        selected = isSelected,
                        onClick = { if (selecting) toggle() else onOpen(shot) },
                        onLongClick = ::toggle,
                    )
                }
            }
        }
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

@Composable
private fun DefaultBanner(onSetDefault: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLargeIncreased,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Make this your default assistant", style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                "Then holding the power button captures the screen into OpenThinking Space.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onSetDefault, shapes = ButtonDefaults.shapes()) { Text("Set as default") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotCard(
    shot: Shot,
    loadThumb: suspend (Uri) -> ImageBitmap?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(null, shot.uri) { value = loadThumb(shot.uri) }
    val scale by animateFloatAsState(if (selected) 0.92f else 1f, label = "select")
    val primary = MaterialTheme.colorScheme.primary
    Card(
        shape = MaterialTheme.shapes.extraLargeIncreased,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = if (selected) BorderStroke(3.dp, primary) else null,
        modifier = Modifier
            .scale(scale)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            thumb?.let {
                Image(
                    bitmap = it,
                    contentDescription = if (shot.noteUri != null) "Screenshot with a voice note" else "Screenshot",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
            if (selected) Box(Modifier.fillMaxSize().background(primary.copy(alpha = 0.25f)))
            if (selecting) {
                Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) primary else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(26.dp)
                        .then(if (selected) Modifier.background(Color.White, CircleShape) else Modifier),
                )
            }
            if (shot.pinned) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(MaterialShapes.Cookie6Sided.toShape())
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(6.dp)
                        .size(16.dp),
                )
            }
            if (shot.noteUri != null) {
                Icon(
                    Icons.Rounded.Mic,
                    contentDescription = "Has a voice note",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(8.dp)
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(isDefault: Boolean, onSetDefault: () -> Unit, onSetup: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(36.dp)
                .size(48.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text("No memories yet", style = MaterialTheme.typography.headlineMediumEmphasized)
        Spacer(Modifier.height(8.dp))
        Text(
            "Hold the power button on any screen, add a voice note if you like, then tap Save.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
