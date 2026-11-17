package com.openthinking.blankassistant.storage

import android.content.Context
import android.content.SharedPreferences

/** The few switches that the capture screen, the gallery and the settings page all read. Same prefs file as the rest of the app. */
object AppSettings {
    const val GROUP_BY_APP = "group_by_app"
    const val HIDE_STATUS_BAR = "hide_status_bar"
    const val LAYOUT = "gallery_layout"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** A tab per app the screenshot was taken in. */
    fun groupByApp(context: Context) = prefs(context).getBoolean(GROUP_BY_APP, true)

    /** Saved screenshots are cut below the status bar. */
    fun hideStatusBar(context: Context) = prefs(context).getBoolean(HIDE_STATUS_BAR, true)
}
