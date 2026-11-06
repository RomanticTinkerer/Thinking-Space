package com.openthinking.blankassistant

import android.app.Application
import android.content.pm.ApplicationInfo
import timber.log.Timber

class ThinkingSpaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The vendored recorder engine logs through Timber; only show it in debuggable builds.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
