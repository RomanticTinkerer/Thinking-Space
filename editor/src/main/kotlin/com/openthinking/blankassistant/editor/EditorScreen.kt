package com.openthinking.blankassistant.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dot.gallery.R
import com.dot.gallery.feature_node.domain.model.editor.TextAnnotation
import com.dot.gallery.feature_node.presentation.edit.components.markup.MarkupPainter
import com.dot.gallery.feature_node.presentation.edit.components.markup.MarkupSelector
import com.dot.gallery.feature_node.presentation.edit.components.markup.TextMarkupOverlay
import com.dot.gallery.feature_node.domain.model.editor.DrawMode
import com.smarttoolfactory.cropper.ImageCropper
import com.smarttoolfactory.cropper.model.AspectRatio
import com.smarttoolfactory.cropper.model.OutlineType
import com.smarttoolfactory.cropper.model.RectCropShape
import com.smarttoolfactory.cropper.settings.CropDefaults
import com.smarttoolfactory.cropper.settings.CropOutlineProperty
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Adjust("Adjust", Icons.Outlined.Tune),
    Filters("Filters", Icons.Outlined.PhotoFilter),
    Markup("Markup", Icons.Outlined.Brush),
    Crop("Crop", Icons.Outlined.Crop),
    Smart("Smart", Icons.Outlined.AutoAwesome);

    /** Adjust and Filters share one [Look] that is drawn live and baked in when you leave them. */
    val isLook get() = this == Adjust || this == Filters
}

/**
 * Host around ReFra's vendored markup UI ([MarkupPainter] + [MarkupSelector] + [TextMarkupOverlay]) and
 * cropper ([ImageCropper]), plus ReFra's filter presets and AI tools (see [Look], [Ml.kt]). ReFra's own
 * EditScreen is tied to its Hilt ViewModel and edit pipeline, so the state here is much leaner.
 *
 * Model: [base] is the working bitmap. Every tab edits it in its own way and "commits" a new bitmap
 * when you leave the tab: markup bakes its strokes, Adjust/Filters bake the [look], Crop and Smart
 * replace it. Save writes [base] after committing whatever is pending.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EditorScreen(
    source: Bitmap?,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember(source) { mutableStateOf(source) }
    var tab by remember { mutableStateOf(Tab.Adjust) }
    var cropping by remember { mutableStateOf(false) }
    var showTextOverlay by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    // Work to run once the painter has baked pending markup into [base].
    var afterApply by remember { mutableStateOf<(() -> Unit)?>(null) }
    var requestApply by remember { mutableStateOf(false) }
    val markup = remember { MarkupState() }
    var look by remember { mutableStateOf(Look()) }
    val smart = remember { SmartState(context, scope) }
    DisposableEffect(smart) { onDispose { smart.reset() } }

    // Small copy of the image for the live Adjust/Filters preview and the filter thumbnails.
    val small by produceState<Bitmap?>(null, base) {
        value = base?.let { b -> withContext(Dispatchers.Default) { resizeForPreview(b, 1080) } }
    }
    val preview by produceState<Bitmap?>(null, small, look) {
        value = small?.let { s -> withContext(Dispatchers.Default) { renderLook(s, look) } }
    }
    val thumb = remember(small) { small?.let { resizeForPreview(it, 160) } }

    fun commitMarkup(next: () -> Unit) {
        if (markup.isEmpty || tab != Tab.Markup) {
            next()
        } else {
            afterApply = next
            requestApply = true
        }
    }

    fun commitLook(next: () -> Unit) {
        val b = base
        if (b == null || look.isDefault) {
            next()
            return
        }
        scope.launch {
            working = true
            val l = look
            val out = withContext(Dispatchers.Default) { renderLook(b, l) }
            base = out
            look = Look()
            working = false
            next()
        }
    }

    /** Bake whatever the current tab has pending, then run [next]. */
    fun commitAll(next: () -> Unit) = commitMarkup { commitLook(next) }

    fun goTo(t: Tab) {
        if (t == tab) return
        commitAll { tab = t }
    }

    // A new working image means any AI selection no longer lines up.
    LaunchedEffect(base) { smart.reset() }
    // Load the subject model as soon as it is likely to be needed.
    LaunchedEffect(tab, smart.mode, base) {
        val b = base
        if (tab == Tab.Smart && smart.mode != SmartMode.Faces && b != null) smart.prepare(b)
    }

    val locked = saving || working

    BackHandler(enabled = !saving) { onCancel() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Top bar: close | save (or apply-crop)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RoundIconButton(
                    icon = Icons.Outlined.Close,
                    description = stringResource(R.string.editor_close),
                    enabled = !saving,
                    onClick = onCancel
                )
                Text(tab.label, style = MaterialTheme.typography.titleLargeEmphasized, color = Color.White)
                RoundIconButton(
                    icon = Icons.Outlined.Check,
                    description = stringResource(
                        if (tab == Tab.Crop) R.string.editor_apply_crop else R.string.editor_save
                    ),
                    enabled = base != null && !locked && !cropping && !smart.busy,
                    onClick = {
                        if (tab == Tab.Crop) {
                            cropping = true
                        } else {
                            commitAll { base?.let(onSave) }
                        }
                    }
                )
            }

            // Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val current = base
                if (current == null || saving || working) {
                    LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                } else if (tab.isLook) {
                    val shown = remember(preview) { preview?.asImageBitmap() }
                    if (shown != null) {
                        Image(shown, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                } else if (tab == Tab.Smart) {
                    SmartCanvas(current, smart)
                } else if (tab == Tab.Markup) {
                    MarkupPainter(
                        modifier = Modifier,
                        bitmap = current,
                        paths = markup.paths,
                        pathsUndone = markup.pathsUndone,
                        addPath = markup::addPath,
                        clearPathsUndone = markup::clearPathsUndone,
                        currentPosition = markup.currentPosition,
                        setCurrentPosition = { markup.currentPosition = it },
                        previousPosition = markup.previousPosition,
                        setPreviousPosition = { markup.previousPosition = it },
                        drawMode = markup.drawMode,
                        currentPath = markup.currentPath,
                        setCurrentPath = { markup.currentPath = it },
                        currentPathProperty = markup.currentPathProperty,
                        setCurrentPathProperty = { markup.currentPathProperty = it },
                        currentImage = current,
                        applyDrawing = { overlay, done ->
                            base = compositeOverlay(current, overlay)
                            markup.clear()
                            done(true)
                        },
                        onNavigateBack = {},
                        requestApply = requestApply,
                        onApplyHandled = {
                            requestApply = false
                            afterApply?.let {
                                afterApply = null
                                it()
                            }
                        },
                        textAnnotations = markup.textAnnotations,
                        onTextAnnotationsChange = { markup.textAnnotations = it },
                        selectedTextIndex = markup.selectedTextIndex,
                        onSelectedTextIndexChange = { markup.selectedTextIndex = it },
                    )
                } else {
                    CropContent(
                        source = current,
                        triggerCrop = cropping,
                        onCropRect = { rect ->
                            base = cropBitmap(current, rect)
                            cropping = false
                            tab = Tab.Markup
                        }
                    )
                }
            }

            // Bottom: undo / redo / add-text + tool selector (markup only)
            if (tab == Tab.Markup && base != null && !locked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = markup::undo, enabled = markup.paths.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = stringResource(R.string.editor_undo),
                            tint = if (markup.paths.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f)
                        )
                    }
                    if (markup.drawMode == DrawMode.Text) {
                        TextButton(onClick = { showTextOverlay = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.editor_add_text), color = Color.White)
                        }
                    }
                    IconButton(onClick = markup::redo, enabled = markup.pathsUndone.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Redo,
                            contentDescription = stringResource(R.string.editor_redo),
                            tint = if (markup.pathsUndone.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f)
                        )
                    }
                }
                MarkupSelector(
                    drawMode = markup.drawMode,
                    setDrawMode = markup::changeDrawMode,
                    drawType = markup.drawType,
                    setDrawType = markup::changeDrawType,
                    isSupportingPanel = false,
                    currentPathProperty = markup.currentPathProperty,
                    setCurrentPathProperty = { markup.currentPathProperty = it },
                    onRequestTextInput = { showTextOverlay = true },
                    textAnnotations = markup.textAnnotations,
                    onTextAnnotationsChange = { markup.textAnnotations = it },
                    selectedTextIndex = markup.selectedTextIndex,
                )
            }

            val current = base
            if (current != null && !locked) {
                when (tab) {
                    Tab.Adjust -> AdjustPanel(look) { look = it }
                    Tab.Filters -> FilterPanel(look, thumb) { look = it }
                    Tab.Smart -> SmartPanel(current, smart) { base = it }
                    else -> {}
                }
            }
            NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        enabled = !locked && base != null,
                        onClick = { goTo(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        }

        if (showTextOverlay) {
            BackHandler { showTextOverlay = false }
            TextMarkupOverlay(
                onDone = { text, color ->
                    markup.selectedTextIndex = markup.textAnnotations.size
                    markup.textAnnotations = markup.textAnnotations + TextAnnotation(
                        text = text,
                        color = color,
                        position = Offset(0.1f, 0.45f)
                    )
                    showTextOverlay = false
                },
                onRemove = { showTextOverlay = false }
            )
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = Color.White)
    }
}

/** Same setup as ReFra's CropActivity.CropContent (free aspect, rounded-rect overlay). */
@Composable
private fun CropContent(
    source: Bitmap,
    triggerCrop: Boolean,
    onCropRect: (RectF) -> Unit,
) {
    val description = stringResource(R.string.editor_crop)
    val preview = remember(source) { resizeForPreview(source, PREVIEW_MAX_DIMENSION).asImageBitmap() }
    val properties = remember {
        CropDefaults.properties(
            cropOutlineProperty = CropOutlineProperty(
                outlineType = OutlineType.RoundedRect,
                cropOutline = RectCropShape(id = 0, title = OutlineType.RoundedRect.name)
            ),
            aspectRatio = AspectRatio.Original,
            overlayRatio = 1f,
            fixedAspectRatio = false
        )
    }
    ImageCropper(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        imageBitmap = preview,
        contentDescription = description,
        cropStyle = CropDefaults.style(
            handleColor = MaterialTheme.colorScheme.tertiary,
            strokeWidth = 1.dp
        ),
        cropProperties = properties,
        crop = triggerCrop,
        onCropStart = {},
        onCropSuccess = {},
        onCropRect = onCropRect
    )
}

private const val PREVIEW_MAX_DIMENSION = 2048

private fun resizeForPreview(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true
    )
}

/** [r] is normalized 0..1 (the cropper's onCropRect contract, see ReFra CropActivity.cropBitmap). */
private fun cropBitmap(source: Bitmap, r: RectF): Bitmap {
    val x = (r.left * source.width).toInt().coerceIn(0, source.width - 1)
    val y = (r.top * source.height).toInt().coerceIn(0, source.height - 1)
    val w = ((r.right - r.left) * source.width).toInt().coerceIn(1, source.width - x)
    val h = ((r.bottom - r.top) * source.height).toInt().coerceIn(1, source.height - y)
    return Bitmap.createBitmap(source, x, y, w, h)
}

/**
 * Draws the transparent markup [overlay] (captured at canvas resolution) onto a copy of [base],
 * scaling to base size. Same math as ReFra's `Markup.apply`.
 */
private fun compositeOverlay(base: Bitmap, overlay: Bitmap): Bitmap {
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    val src = if (overlay.config == Bitmap.Config.HARDWARE) overlay.copy(Bitmap.Config.ARGB_8888, false) else overlay
    val matrix = Matrix().apply {
        setScale(out.width / src.width.toFloat(), out.height / src.height.toFloat())
    }
    Canvas(out).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}
