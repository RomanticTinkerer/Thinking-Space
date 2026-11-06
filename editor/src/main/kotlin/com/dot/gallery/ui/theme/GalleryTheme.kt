package com.dot.gallery.ui.theme

import androidx.compose.runtime.Composable
import com.openthinking.blankassistant.ui.OtsTheme

// Stand-in for ReFra's GalleryTheme: the editor uses the app's own expressive theme (dynamic colours, Google Sans Flex).
@Composable
fun GalleryTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    OtsTheme(forceDark = darkTheme, content = content)
}
