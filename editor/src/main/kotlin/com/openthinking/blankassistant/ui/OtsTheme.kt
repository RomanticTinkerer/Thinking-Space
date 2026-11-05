package com.openthinking.blankassistant.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.dot.gallery.R

/** SharedPreferences "app" key: follow the wallpaper's colours (Android 12+). On by default. */
const val DYNAMIC_COLOR_KEY = "dynamic_color"

fun appPrefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)

/** Roboto Flex is one variable font file; each weight is the same file with a different `wght` setting. */
private val RobotoFlex = FontFamily(
    listOf(300, 400, 500, 600, 700, 800).map { w ->
        Font(R.font.roboto_flex, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun robotoFlexTypography(): Typography {
    val t = Typography()
    fun TextStyle.flex() = copy(fontFamily = RobotoFlex)
    return Typography(
        displayLarge = t.displayLarge.flex(), displayMedium = t.displayMedium.flex(), displaySmall = t.displaySmall.flex(),
        headlineLarge = t.headlineLarge.flex(), headlineMedium = t.headlineMedium.flex(), headlineSmall = t.headlineSmall.flex(),
        titleLarge = t.titleLarge.flex(), titleMedium = t.titleMedium.flex(), titleSmall = t.titleSmall.flex(),
        bodyLarge = t.bodyLarge.flex(), bodyMedium = t.bodyMedium.flex(), bodySmall = t.bodySmall.flex(),
        labelLarge = t.labelLarge.flex(), labelMedium = t.labelMedium.flex(), labelSmall = t.labelSmall.flex(),
        displayLargeEmphasized = t.displayLargeEmphasized.flex(), displayMediumEmphasized = t.displayMediumEmphasized.flex(),
        displaySmallEmphasized = t.displaySmallEmphasized.flex(),
        headlineLargeEmphasized = t.headlineLargeEmphasized.flex(), headlineMediumEmphasized = t.headlineMediumEmphasized.flex(),
        headlineSmallEmphasized = t.headlineSmallEmphasized.flex(),
        titleLargeEmphasized = t.titleLargeEmphasized.flex(), titleMediumEmphasized = t.titleMediumEmphasized.flex(),
        titleSmallEmphasized = t.titleSmallEmphasized.flex(),
        bodyLargeEmphasized = t.bodyLargeEmphasized.flex(), bodyMediumEmphasized = t.bodyMediumEmphasized.flex(),
        bodySmallEmphasized = t.bodySmallEmphasized.flex(),
        labelLargeEmphasized = t.labelLargeEmphasized.flex(), labelMediumEmphasized = t.labelMediumEmphasized.flex(),
        labelSmallEmphasized = t.labelSmallEmphasized.flex(),
    )
}

/**
 * Material 3 Expressive: spring motion, expressive shapes, Roboto Flex type. Colours follow the
 * wallpaper (Material You) on Android 12+ unless switched off in Settings, light/dark per system
 * (or [forceDark], which the editor uses). Lives in the editor module so the editor and the app share it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OtsTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val dark = forceDark ?: isSystemInDarkTheme()
    val context = LocalContext.current
    val prefs = remember { appPrefs(context) }
    var dynamic by remember { mutableStateOf(prefs.getBoolean(DYNAMIC_COLOR_KEY, true)) }
    // Settings flips the switch from another activity; follow it live.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == DYNAMIC_COLOR_KEY) dynamic = p.getBoolean(DYNAMIC_COLOR_KEY, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = remember { robotoFlexTypography() },
        content = content,
    )
}
