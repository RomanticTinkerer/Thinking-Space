package com.openthinking.blankassistant.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import com.dot.gallery.feature.editor.adjustments.filters.ImageFilterTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Adjust

@Composable
internal fun AdjustPanel(look: Look, onLook: (Look) -> Unit) {
    var selected by remember { mutableStateOf(Tool.Brightness) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(selected.label, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                "${(look[selected] * 100).roundToInt()}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            TextButton(onClick = { onLook(look.with(selected, 0f)) }, enabled = look[selected] != 0f) { Text("Reset") }
        }
        Slider(
            value = look[selected],
            onValueChange = { onLook(look.with(selected, it)) },
            valueRange = selected.min..1f,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Tool.entries.toList()) { tool ->
                FilterChip(
                    selected = tool == selected,
                    onClick = { selected = tool },
                    label = { Text(tool.label) },
                    leadingIcon = { Icon(tool.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Filters

@Composable
internal fun FilterPanel(look: Look, thumb: Bitmap?, onLook: (Look) -> Unit) {
    val image = remember(thumb) { thumb?.asImageBitmap() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (look.filter != ImageFilterTypes.Original) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Intensity", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    "${(look.intensity * 100).roundToInt()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Slider(value = look.intensity, onValueChange = { onLook(look.copy(intensity = it)) })
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            items(ImageFilterTypes.entries.toList()) { filter ->
                val on = filter == look.filter
                val matrix = remember(filter) { filter.createImageFilter().colorMatrix() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                BorderStroke(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.primary),
                                RoundedCornerShape(16.dp),
                            )
                            .clickable { onLook(look.copy(filter = filter)) }
                    ) {
                        if (image != null) {
                            Image(
                                bitmap = image,
                                contentDescription = filter.name,
                                contentScale = ContentScale.Crop,
                                colorFilter = matrix?.let { ColorFilter.colorMatrix(it) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        filter.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Smart (on-device AI)

internal enum class SmartMode(val label: String) {
    Cutout("Cut out"), Background("Blur background"), Faces("Blur faces")
}

/**
 * State for the Smart tab. The subject picker (MobileSAM) is built for one image and thrown away when
 * the image changes. All model work runs on a background thread, one job at a time.
 */
internal class SmartState(private val context: Context, private val scope: CoroutineScope) {
    var mode by mutableStateOf(SmartMode.Cutout)
    var adding by mutableStateOf(true)
    var taps by mutableStateOf(emptyList<Tap>())
    var mask by mutableStateOf<Mask?>(null)
    var overlay by mutableStateOf<ImageBitmap?>(null)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    private var picker: SubjectPicker? = null
    private var pickerFor: Bitmap? = null
    private val lock = Mutex()

    /** Forget the selection and the model state. Call when the working image changes. */
    fun reset() {
        picker?.close()
        picker = null
        pickerFor = null
        taps = emptyList()
        mask = null
        overlay = null
        message = null
    }

    /** Warm the model up (a few seconds) so the first tap answers fast. */
    suspend fun prepare(base: Bitmap) = lock.withLock {
        if (pickerFor === base) return@withLock
        busy = true
        try {
            withContext(Dispatchers.Default) {
                picker?.close()
                pickerFor = null
                picker = SubjectPicker(context, base).also { it.prepare() }
                pickerFor = base
            }
        } catch (e: Throwable) {
            message = "Could not load the AI model"
        } finally {
            busy = false
        }
    }

    fun tap(base: Bitmap, x: Float, y: Float) {
        if (busy || mode == SmartMode.Faces) return
        select(base, taps + Tap(x, y, adding))
    }

    fun undoTap(base: Bitmap) {
        if (busy || taps.isEmpty()) return
        val next = taps.dropLast(1)
        if (next.isEmpty()) { taps = next; mask = null; overlay = null } else select(base, next)
    }

    private fun select(base: Bitmap, next: List<Tap>) {
        scope.launch {
            prepare(base)
            val p = picker?.takeIf { pickerFor === base } ?: return@launch
            busy = true
            message = null
            val found = withContext(Dispatchers.Default) { runCatching { p.pick(next)?.let { it to it.overlay(0x992196F3.toInt()).asImageBitmap() } } }
            busy = false
            taps = next
            val (m, o) = found.getOrNull() ?: (null to null)
            mask = m
            overlay = o
            if (m == null) message = if (found.isFailure) "Selection failed" else "Nothing found there, tap the subject"
        }
    }

    /** Cut out or blur the background with the current selection. [done] receives the new image. */
    fun applyMask(base: Bitmap, done: (Bitmap) -> Unit) {
        val m = mask ?: return
        val cut = mode == SmartMode.Cutout
        scope.launch {
            busy = true
            val out = withContext(Dispatchers.Default) { runCatching { if (cut) m.cutout(base) else m.blurBackground(base) }.getOrNull() }
            busy = false
            if (out == null) message = "Nothing to apply" else { reset(); done(out) }
        }
    }

    fun detectFaces(base: Bitmap, done: (Bitmap) -> Unit) {
        scope.launch {
            busy = true
            message = null
            val r = withContext(Dispatchers.Default) { runCatching { blurFaces(context, base) } }
            busy = false
            val (out, count) = r.getOrNull() ?: run { message = "Face detection failed"; return@launch }
            if (count == 0) message = "No faces found" else { reset(); message = "Blurred $count face${if (count > 1) "s" else ""}"; done(out) }
        }
    }
}

/** The image with the selection tinted on top. Tap a subject to select it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SmartCanvas(base: Bitmap, smart: SmartState) {
    val image = remember(base) { base.asImageBitmap() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // ContentScale.Fit, centred: image pixel -> screen = offset + pixel * scale
    val scale = if (box == IntSize.Zero) 1f else min(box.width / base.width.toFloat(), box.height / base.height.toFloat())
    val offset = Offset((box.width - base.width * scale) / 2f, (box.height - base.height * scale) / 2f)
    val dot = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(base, scale, box) {
                detectTapGestures { p ->
                    val x = (p.x - offset.x) / scale
                    val y = (p.y - offset.y) / scale
                    if (x in 0f..base.width.toFloat() && y in 0f..base.height.toFloat()) smart.tap(base, x, y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        smart.overlay?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        Canvas(Modifier.fillMaxSize()) {
            smart.taps.forEach {
                val c = Offset(offset.x + it.x * scale, offset.y + it.y * scale)
                drawCircle(if (it.add) dot else Color.Red, radius = 9.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, radius = 3.dp.toPx(), center = c)
            }
        }
        if (smart.busy) LoadingIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun SmartPanel(base: Bitmap, smart: SmartState, onResult: (Bitmap) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SmartMode.entries.toList()) { m ->
                FilterChip(
                    selected = smart.mode == m,
                    enabled = !smart.busy,
                    onClick = { smart.reset(); smart.mode = m },
                    label = { Text(m.label) },
                    leadingIcon = {
                        Icon(
                            when (m) {
                                SmartMode.Cutout -> Icons.Outlined.ContentCut
                                SmartMode.Background -> Icons.Outlined.BlurOn
                                SmartMode.Faces -> Icons.Outlined.Face
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        if (smart.mode == SmartMode.Faces) {
            Text(
                smart.message ?: "Finds faces in the picture and blurs them. Runs on your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
            Button(onClick = { smart.detectFaces(base, onResult) }, enabled = !smart.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Blur faces", modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Text(
                smart.message ?: when {
                    smart.busy && smart.taps.isEmpty() -> "Getting the AI model ready..."
                    smart.taps.isEmpty() -> "Tap the subject."
                    else -> "Tap more spots to refine. Choose Remove to leave an area out."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Start,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = smart.adding, onClick = { smart.adding = true }, label = { Text("Add") })
                FilterChip(selected = !smart.adding, onClick = { smart.adding = false }, label = { Text("Remove") })
                TextButton(onClick = { smart.undoTap(base) }, enabled = smart.taps.isNotEmpty() && !smart.busy) { Text("Undo") }
                Box(Modifier.weight(1f))
                Button(onClick = { smart.applyMask(base, onResult) }, enabled = smart.mask != null && !smart.busy) {
                    Text(if (smart.mode == SmartMode.Cutout) "Cut out" else "Blur")
                }
            }
        }
    }
}
