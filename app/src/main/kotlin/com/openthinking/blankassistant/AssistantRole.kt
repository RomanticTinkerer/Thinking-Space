package com.openthinking.blankassistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

fun isDefaultAssistant(context: Context): Boolean =
    context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

/**
 * The system's "set as default digital assistant" dialog. Android only lets the user make the
 * switch, so this is as close to "set it for me" as an app gets; falls back to the settings page.
 */
fun assistantRoleIntent(context: Context): Intent {
    val roles = context.getSystemService(RoleManager::class.java)
    return if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
        roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    } else {
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
}

/** Opens the first settings page the phone actually has, most specific first. Some ROMs drop the assistant one. */
fun openAssistantSettings(context: Context) {
    val pages = listOf(
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        Settings.ACTION_SETTINGS,
    )
    for (action in pages) if (runCatching { context.startActivity(Intent(action)) }.isSuccess) return
}

/**
 * Asks for the assistant role. MIUI / HyperOS (and some other forks) ignore the request: the dialog closes
 * at once and nothing changes. When that happens we open the settings pages instead.
 * Create it as an activity property, because it registers a result launcher.
 */
class AssistantRoleLauncher(private val activity: ComponentActivity) {
    private var startedAt = 0L
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // ponytail: "came back within 1.5 s" is the signal for a dialog that never showed; a real user takes longer.
        if (!isDefaultAssistant(activity) && SystemClock.elapsedRealtime() - startedAt < 1500) openAssistantSettings(activity)
    }

    fun launch() {
        startedAt = SystemClock.elapsedRealtime()
        runCatching { launcher.launch(assistantRoleIntent(activity)) }.onFailure { openAssistantSettings(activity) }
    }
}
