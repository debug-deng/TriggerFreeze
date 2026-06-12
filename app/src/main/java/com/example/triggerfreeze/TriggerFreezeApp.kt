package com.example.triggerfreeze

import android.app.Application
import android.content.Context

/**
 * Custom [Application] so we can install [CrashReporter] as early as
 * possible — in [attachBaseContext], which runs BEFORE any ContentProvider
 * (including ShizukuProvider) is created. This way even a crash in the
 * provider's onCreate gets caught and logged.
 */
class TriggerFreezeApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // First thing with a Context — no other lifecycle method runs earlier
        // that has a Context. From here on, any uncaught exception anywhere
        // in the process goes to logcat AND crash.log.
        CrashReporter.install(base)
    }
}
