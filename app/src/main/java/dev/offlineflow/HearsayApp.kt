package dev.offlineflow

import android.app.Application
import com.google.android.material.color.DynamicColors

/** Applies wallpaper-based Material You colors on Android 12+; the brand indigo theme otherwise. */
class HearsayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        if (BuildConfig.DEBUG) dev.offlineflow.audio.Recorder.demoDir = getExternalFilesDir("demo")
    }
}
