package dev.offlineflow

import android.app.Application
import com.google.android.material.color.DynamicColors

/** Starts testing diagnostics when compiled in, and applies wallpaper-based Material You colors on Android 12+; the brand indigo theme otherwise. */
class HearsayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is reported (testing builds with the switch on).
        dev.offlineflow.telemetry.Telemetry.init(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        Prefs.applySpeculation(this)
        if (BuildConfig.DEBUG) dev.offlineflow.audio.Recorder.demoDir = getExternalFilesDir("demo")
    }
}
