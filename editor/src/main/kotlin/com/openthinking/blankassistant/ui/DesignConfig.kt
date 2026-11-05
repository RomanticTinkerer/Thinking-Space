package com.openthinking.blankassistant.ui

import android.content.SharedPreferences
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * The whole look of the app in one file: corner sizes, the type scale and the colour options.
 * Change a number here and every screen follows. OtsTheme is the only code that reads it.
 *
 * The ideas (a rounder corner scale, headlines that are heavier and wider than body text, labels a
 * bit bolder, light and dark chosen separately, an optional pure black) come from how the LastWave
 * music player is styled. LastWave is GPL-3.0, so none of its code is used here, only the approach.
 */
object DesignConfig {

    // ---- Shapes ----
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

    /** Big surfaces that should read as one soft block (settings groups, hero cards). */
    val heroCorner = 28.dp

    /** The small corner between neighbouring rows of one list. */
    val joinCorner = 6.dp

    // ---- Type: Roboto Flex axes per text style ----

    /** [weight] is `wght` (100..1000), [width] is `wdth` (25..151, 100 is normal), [opticalSize] is `opsz`. */
    class Face(val weight: Int, val width: Float, val opticalSize: Float)

    object Type {
        val displayLarge = Face(740, 118f, 57f)
        val displayMedium = Face(720, 116f, 45f)
        val displaySmall = Face(700, 114f, 36f)
        val headlineLarge = Face(760, 116f, 32f)
        val headlineMedium = Face(740, 114f, 28f)
        val headlineSmall = Face(720, 112f, 24f)
        val titleLarge = Face(620, 108f, 22f)
        val titleMedium = Face(600, 105f, 16f)
        val titleSmall = Face(580, 104f, 14f)
        val bodyLarge = Face(420, 100f, 16f)
        val bodyMedium = Face(420, 100f, 14f)
        val bodySmall = Face(410, 100f, 12f)
        val labelLarge = Face(640, 104f, 14f)
        val labelMedium = Face(620, 104f, 12f)
        val labelSmall = Face(620, 104f, 11f)

        /** The "Emphasized" styles of Material 3 Expressive use the same face, this much heavier. */
        const val emphasisBoost = 120
    }

    // ---- Colour ----
    enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

    const val DEFAULT_DYNAMIC = true
    val DEFAULT_MODE = ThemeMode.System
    const val DEFAULT_PURE_BLACK = false

    const val KEY_DYNAMIC = "dynamic_color"
    const val KEY_MODE = "theme_mode"
    const val KEY_PURE_BLACK = "pure_black"
}

/** The choices the user can change in Settings. Stored in the "app" preferences. */
data class DesignSettings(
    val dynamic: Boolean = DesignConfig.DEFAULT_DYNAMIC,
    val mode: DesignConfig.ThemeMode = DesignConfig.DEFAULT_MODE,
    val pureBlack: Boolean = DesignConfig.DEFAULT_PURE_BLACK,
) {
    companion object {
        fun read(p: SharedPreferences) = DesignSettings(
            dynamic = p.getBoolean(DesignConfig.KEY_DYNAMIC, DesignConfig.DEFAULT_DYNAMIC),
            mode = DesignConfig.ThemeMode.entries.firstOrNull { it.name == p.getString(DesignConfig.KEY_MODE, null) }
                ?: DesignConfig.DEFAULT_MODE,
            pureBlack = p.getBoolean(DesignConfig.KEY_PURE_BLACK, DesignConfig.DEFAULT_PURE_BLACK),
        )
    }
}
