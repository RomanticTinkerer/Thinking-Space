package com.openthinking.blankassistant.ui

import android.content.SharedPreferences
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/*
 * The whole look of the app in plain data: corner sizes, the type scale and motion. A [DesignLanguage]
 * is one complete set of those. M3 Expressive is the default and the only one so far; to add another,
 * write a new DesignLanguage below and put it in [DesignLanguages.all]. OtsTheme is the only code that
 * reads it (screens get the active one from [LocalDesign]).
 *
 * The ideas (a rounder corner scale, headlines that are heavier and wider than body text, labels a
 * bit bolder, light and dark chosen separately, an optional pure black) come from how the LastWave
 * music player is styled. LastWave is GPL-3.0, so none of its code is used here, only the approach.
 */

/** Roboto Flex axes for one text style: `wght` (100..1000), `wdth` (25..151, 100 is normal), `opsz`. */
class Face(val weight: Int, val width: Float, val opticalSize: Float)

class TypeScale(
    val displayLarge: Face, val displayMedium: Face, val displaySmall: Face,
    val headlineLarge: Face, val headlineMedium: Face, val headlineSmall: Face,
    val titleLarge: Face, val titleMedium: Face, val titleSmall: Face,
    val bodyLarge: Face, val bodyMedium: Face, val bodySmall: Face,
    val labelLarge: Face, val labelMedium: Face, val labelSmall: Face,
    /** The "Emphasized" styles of Material 3 Expressive use the same face, this much heavier. */
    val emphasisBoost: Int = 120,
)

class DesignLanguage(
    val id: String,
    val label: String,
    val shapes: Shapes,
    /** Big surfaces that should read as one soft block (settings groups, hero cards). */
    val heroCorner: Dp,
    /** The small corner between neighbouring rows of one list. */
    val joinCorner: Dp,
    val type: TypeScale,
    /** Springy Material 3 Expressive motion, or the plain standard one. */
    val expressiveMotion: Boolean,
)

object DesignLanguages {
    val M3Expressive = DesignLanguage(
        id = "m3_expressive",
        label = "Material 3 Expressive",
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        heroCorner = 28.dp,
        joinCorner = 6.dp,
        type = TypeScale(
            displayLarge = Face(740, 118f, 57f), displayMedium = Face(720, 116f, 45f), displaySmall = Face(700, 114f, 36f),
            headlineLarge = Face(760, 116f, 32f), headlineMedium = Face(740, 114f, 28f), headlineSmall = Face(720, 112f, 24f),
            titleLarge = Face(620, 108f, 22f), titleMedium = Face(600, 105f, 16f), titleSmall = Face(580, 104f, 14f),
            bodyLarge = Face(420, 100f, 16f), bodyMedium = Face(420, 100f, 14f), bodySmall = Face(410, 100f, 12f),
            labelLarge = Face(640, 104f, 14f), labelMedium = Face(620, 104f, 12f), labelSmall = Face(620, 104f, 11f),
        ),
        expressiveMotion = true,
    )

    val all = listOf(M3Expressive)
    val default = M3Expressive

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: default
}

/** The active design language, for screens that need a value from it (OtsTheme provides it). */
val LocalDesign = staticCompositionLocalOf { DesignLanguages.default }

object DesignConfig {
    enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

    const val DEFAULT_DYNAMIC = true
    val DEFAULT_MODE = ThemeMode.System
    const val DEFAULT_PURE_BLACK = false

    const val KEY_DYNAMIC = "dynamic_color"
    const val KEY_MODE = "theme_mode"
    const val KEY_PURE_BLACK = "pure_black"
    const val KEY_LANGUAGE = "design_language"
}

/** The choices the user can change in Settings. Stored in the "app" preferences. */
data class DesignSettings(
    val dynamic: Boolean = DesignConfig.DEFAULT_DYNAMIC,
    val mode: DesignConfig.ThemeMode = DesignConfig.DEFAULT_MODE,
    val pureBlack: Boolean = DesignConfig.DEFAULT_PURE_BLACK,
    val language: DesignLanguage = DesignLanguages.default,
) {
    companion object {
        fun read(p: SharedPreferences) = DesignSettings(
            dynamic = p.getBoolean(DesignConfig.KEY_DYNAMIC, DesignConfig.DEFAULT_DYNAMIC),
            mode = DesignConfig.ThemeMode.entries.firstOrNull { it.name == p.getString(DesignConfig.KEY_MODE, null) }
                ?: DesignConfig.DEFAULT_MODE,
            pureBlack = p.getBoolean(DesignConfig.KEY_PURE_BLACK, DesignConfig.DEFAULT_PURE_BLACK),
            language = DesignLanguages.byId(p.getString(DesignConfig.KEY_LANGUAGE, null)),
        )
    }
}
