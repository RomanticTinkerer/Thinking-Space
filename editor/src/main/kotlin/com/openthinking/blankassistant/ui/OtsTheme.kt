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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.dot.gallery.R

fun appPrefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)

/** One font file (Google Sans Flex is variable); each text style picks its own axes from the design language. */
private fun family(f: Face, weightBoost: Int = 0): FontFamily {
    val w = (f.weight + weightBoost).coerceIn(100, 1000)
    return FontFamily(
        Font(
            R.font.google_sans_flex,
            FontWeight(w),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(w),
                FontVariation.width(f.width),
                FontVariation.Setting("ROND", f.round),
                FontVariation.Setting("opsz", f.opticalSize),
            ),
        )
    )
}

private fun TextStyle.face(f: Face, boost: Int = 0) =
    copy(fontFamily = family(f, boost), fontWeight = FontWeight((f.weight + boost).coerceIn(100, 1000)))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun flexTypography(f: TypeScale): Typography {
    val t = Typography()
    val b = f.emphasisBoost
    return Typography(
        displayLarge = t.displayLarge.face(f.displayLarge), displayMedium = t.displayMedium.face(f.displayMedium),
        displaySmall = t.displaySmall.face(f.displaySmall),
        headlineLarge = t.headlineLarge.face(f.headlineLarge), headlineMedium = t.headlineMedium.face(f.headlineMedium),
        headlineSmall = t.headlineSmall.face(f.headlineSmall),
        titleLarge = t.titleLarge.face(f.titleLarge), titleMedium = t.titleMedium.face(f.titleMedium),
        titleSmall = t.titleSmall.face(f.titleSmall),
        bodyLarge = t.bodyLarge.face(f.bodyLarge), bodyMedium = t.bodyMedium.face(f.bodyMedium),
        bodySmall = t.bodySmall.face(f.bodySmall),
        labelLarge = t.labelLarge.face(f.labelLarge), labelMedium = t.labelMedium.face(f.labelMedium),
        labelSmall = t.labelSmall.face(f.labelSmall),
        displayLargeEmphasized = t.displayLargeEmphasized.face(f.displayLarge, b),
        displayMediumEmphasized = t.displayMediumEmphasized.face(f.displayMedium, b),
        displaySmallEmphasized = t.displaySmallEmphasized.face(f.displaySmall, b),
        headlineLargeEmphasized = t.headlineLargeEmphasized.face(f.headlineLarge, b),
        headlineMediumEmphasized = t.headlineMediumEmphasized.face(f.headlineMedium, b),
        headlineSmallEmphasized = t.headlineSmallEmphasized.face(f.headlineSmall, b),
        titleLargeEmphasized = t.titleLargeEmphasized.face(f.titleLarge, b),
        titleMediumEmphasized = t.titleMediumEmphasized.face(f.titleMedium, b),
        titleSmallEmphasized = t.titleSmallEmphasized.face(f.titleSmall, b),
        bodyLargeEmphasized = t.bodyLargeEmphasized.face(f.bodyLarge, b),
        bodyMediumEmphasized = t.bodyMediumEmphasized.face(f.bodyMedium, b),
        bodySmallEmphasized = t.bodySmallEmphasized.face(f.bodySmall, b),
        labelLargeEmphasized = t.labelLargeEmphasized.face(f.labelLarge, b),
        labelMediumEmphasized = t.labelMediumEmphasized.face(f.labelMedium, b),
        labelSmallEmphasized = t.labelSmallEmphasized.face(f.labelSmall, b),
    )
}

/**
 * The active [DesignLanguage] (Material 3 Expressive by default) applied to Material 3. Colours follow the wallpaper (Material You,
 * Android 12+) unless switched off; light, dark or system comes from Settings, and dark can go pure black.
 * [forceDark] overrides the mode (the editor uses it). Lives in the editor module so the app shares it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OtsTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { appPrefs(context) }
    var settings by remember { mutableStateOf(DesignSettings.read(prefs)) }
    // Settings changes things from another activity; follow it live.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> settings = DesignSettings.read(p) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val lang = settings.language
    val dark = forceDark ?: when (settings.mode) {
        DesignConfig.ThemeMode.System -> isSystemInDarkTheme()
        DesignConfig.ThemeMode.Light -> false
        DesignConfig.ThemeMode.Dark -> true
    }
    val base = when {
        settings.dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    val colors = if (dark && settings.pureBlack) {
        base.copy(
            background = Color.Black, surface = Color.Black,
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color.Black,
        )
    } else base
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = if (lang.expressiveMotion) MotionScheme.expressive() else MotionScheme.standard(),
        shapes = lang.shapes,
        typography = remember(lang) { flexTypography(lang.type) },
    ) {
        CompositionLocalProvider(LocalDesign provides lang, content = content)
    }
}
