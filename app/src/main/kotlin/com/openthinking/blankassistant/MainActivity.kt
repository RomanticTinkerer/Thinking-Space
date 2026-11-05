@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.openthinking.blankassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openthinking.blankassistant.storage.MemoryMeta
import com.openthinking.blankassistant.ui.DYNAMIC_COLOR_KEY
import com.openthinking.blankassistant.ui.OtsTheme
import com.openthinking.blankassistant.ui.appPrefs

/**
 * Settings, opened from the corner of the gallery. It is also the assistant's "settings" page and
 * declares ACTION_ASSIST (see the manifest), which the Android ASSISTANT role requires.
 */
class MainActivity : ComponentActivity() {

    private var isDefault by mutableStateOf(false)
    private var hasMic by mutableStateOf(false)
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OtsTheme {
                SettingsScreen(
                    isDefault = isDefault,
                    hasMic = hasMic,
                    version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                    onBack = ::finish,
                    onDefaultAssistant = { startActivity(assistantRoleIntent(this)) },
                    onMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onRescan = { MemoryMeta(this).clearOcr() },
                    onOpen = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
                )
            }
        }
        refresh()
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false) && !hasMic) askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        isDefault = isDefaultAssistant(this)
        hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "request_mic"
    }
}

private const val REPO = "https://github.com/RomanticTinkerer/OpenThinking-Space"

@Composable
private fun SettingsScreen(
    isDefault: Boolean,
    hasMic: Boolean,
    version: String,
    onBack: () -> Unit,
    onDefaultAssistant: () -> Unit,
    onMic: () -> Unit,
    onRescan: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { appPrefs(context) }
    var dynamic by remember { mutableStateOf(prefs.getBoolean(DYNAMIC_COLOR_KEY, true)) }
    var rescanned by remember { mutableStateOf(false) }
    val canDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                subtitle = { Text("OpenThinking Space") },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Section(
                    "Assistant",
                    {
                        SettingRow(
                            Icons.Rounded.AutoAwesome, "Default assistant",
                            if (isDefault) "On. Hold the power button to capture the screen." else "Off. Tap to choose OpenThinking Space.",
                            onClick = onDefaultAssistant,
                        )
                    },
                    {
                        SettingRow(
                            Icons.Rounded.Mic, "Microphone",
                            if (hasMic) "Allowed for voice notes." else "Needed for voice notes. Tap to allow.",
                            onClick = if (hasMic) null else onMic,
                        )
                    },
                    {
                        SettingRow(
                            Icons.Rounded.Info, "Use screenshot",
                            "In your phone\u2019s Digital assistant settings, switch on \u201cUse screenshot\u201d. That is how the assistant gets the screen.",
                        )
                    },
                )
            }
            item {
                Section(
                    "Appearance",
                    {
                        SettingRow(
                            Icons.Rounded.Palette, "Wallpaper colours",
                            if (canDynamic) "Colours follow your wallpaper." else "Needs Android 12 or later.",
                            onClick = if (canDynamic) ({ dynamic = !dynamic; prefs.edit().putBoolean(DYNAMIC_COLOR_KEY, dynamic).apply() }) else null,
                            trailing = {
                                Switch(
                                    checked = dynamic && canDynamic,
                                    onCheckedChange = null, // the whole row handles the tap
                                    enabled = canDynamic,
                                )
                            },
                        )
                    },
                )
            }
            item {
                Section(
                    "Text in screenshots",
                    {
                        SettingRow(
                            Icons.Rounded.TextFields, "Read all screenshots again",
                            if (rescanned) "Done. Open the gallery and it starts reading." else "Clears the saved text. Every screenshot is read again when you open the gallery.",
                            onClick = { onRescan(); rescanned = true },
                            trailing = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        )
                    },
                )
            }
            item {
                Section(
                    "About",
                    { SettingRow(Icons.Rounded.Info, "Version", version) },
                    { SettingRow(Icons.Rounded.Code, "Source code", "github.com/RomanticTinkerer/OpenThinking-Space", onClick = { onOpen(REPO) }) },
                    {
                        SettingRow(
                            Icons.Rounded.Description, "Open source licences",
                            "Tesseract, ReFra, Dimowner recorder and Roboto Flex.",
                            onClick = { onOpen("$REPO/blob/main/THIRD_PARTY_NOTICES.md") },
                        )
                    },
                )
            }
        }
    }
}

/** A titled group of rows. Outer corners are big, the corners between rows are small, so they read as one list. */
@Composable
private fun Section(title: String, vararg rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        rows.forEachIndexed { i, row ->
            val top = if (i == 0) 28.dp else 6.dp
            val bottom = if (i == rows.lastIndex) 28.dp else 6.dp
            Surface(
                shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { row() }
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialShapes.Cookie6Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}
