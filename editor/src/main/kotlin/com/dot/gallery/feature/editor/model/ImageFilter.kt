package com.dot.gallery.feature.editor.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ColorMatrix

/**
 * Minimal stand-ins for ReFra's `Adjustment` / `ImageFilter` interfaces. ReFra's versions carry its
 * tiled-bake engine hooks (TileBehavior etc.), which this editor does not use; the vendored filter
 * presets in `adjustments/filters/` only need these two members.
 */
interface Adjustment {
    val name: String
    fun apply(bitmap: Bitmap): Bitmap
}

interface ImageFilter : Adjustment {
    fun colorMatrix(): ColorMatrix?
}
