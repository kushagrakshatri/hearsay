package dev.offlineflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guided first run, one step per screen: welcome, microphone, models, enable the keyboard,
 * switch to it, try it. Steps that are already done are skipped automatically.
 */
class SetupActivity : AppCompatActivity() {
    private enum class Step { WELCOME, MIC, MODELS, ENABLE, SWITCH, TRY }

    private lateinit var ui: Ui
    private lateinit var models: ModelManager
    private lateinit var progress: LinearProgressIndicator
    private lateinit var body: LinearLayout
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton
    private var step = Step.WELCOME
    private var downloading = false
    private val bars = mutableMapOf<ModelManager.Model, LinearProgressIndicator>()
    private val labels = mutableMapOf<ModelManager.Model, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); models = ModelManager(this)

        progress = LinearProgressIndicator(this).apply {
            max = Step.entries.size - 1; trackCornerRadius = ui.dp(4); isIndeterminate = false
        }
        body = ui.column()
        primary = ui.filled("") { onPrimary() }
        secondary = ui.text("") { onSecondary() }

        val root = ui.column(ui.dp(24), ui.dp(16)).apply {
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(context).apply {
                isFillViewport = true
                addView(FrameLayout(context).apply {
                    setPadding(0, ui.dp(56), 0, ui.dp(16))
                    addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(secondary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        Ui.edgeToEdge(this, root)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val prev = Step.entries.getOrNull(step.ordinal - 1)
                if (prev == null || prev == Step.WELCOME && step == Step.MIC) finish() else show(prev, auto = false)
            }
        })
        show(firstOpen(), auto = false)
    }

    override fun onResume() { super.onResume(); advanceIfDone() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) advanceIfDone() // the keyboard picker is a dialog: no onResume after it
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); advanceIfDone()
    }

    // ------------------------------------------------------------------ state

    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun modelsOk() = models.isReady(models.selected) && (models.selectedCleanup?.let { models.isReady(it) } ?: true)
    private fun enabledOk() = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
    private fun currentOk() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun done(s: Step) = when (s) {
        Step.WELCOME -> false
        Step.MIC -> micOk()
        Step.MODELS -> modelsOk()
        Step.ENABLE -> enabledOk()
        Step.SWITCH -> currentOk()
        Step.TRY -> false
    }

    /** Welcome on a fresh install; otherwise the first step still to do. */
    private fun firstOpen(): Step =
        if (!micOk() && !models.isReady(models.selected)) Step.WELCOME
        else Step.entries.drop(1).firstOrNull { !done(it) } ?: Step.TRY

    private fun advanceIfDone() {
        if (step != Step.WELCOME && step != Step.TRY && !downloading && done(step)) {
            show(Step.entries.drop(step.ordinal + 1).firstOrNull { !done(it) } ?: Step.TRY, auto = true)
        } else if (step == Step.MODELS) refreshModels()
    }

    // ------------------------------------------------------------------ screens

    private fun show(s: Step, auto: Boolean) {
        step = s
        progress.setProgressCompat(s.ordinal, auto)
        body.removeAllViews(); bars.clear(); labels.clear()
        secondary.visibility = View.VISIBLE
        when (s) {
            Step.WELCOME -> {
                body.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
                    LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                header("Talk. It types.", "Hearsay turns your voice into clean, punctuated text in any app. " +
                    "Speech is transcribed on this phone, so it works offline and your voice stays yours.", center = true)
                primary.text = "Get started"; secondary.text = "Skip setup"
            }
            Step.MIC -> {
                header("Allow the microphone", "Hearsay listens only while you hold or tap the mic. " +
                    "Audio is processed on this phone and never saved.")
                bullet(R.drawable.ic_lock, "Nothing is recorded in the background.")
                primary.text = "Allow microphone"; secondary.text = "Not now"
            }
            Step.MODELS -> {
                val list = listOfNotNull(models.selected, models.selectedCleanup)
                val mb = list.filter { !models.isReady(it) }.sumOf { it.approxMb }
                header("Download the voice models", "These run on your phone, so Hearsay works without a connection. " +
                    "It's a one-time download of about ${"%.1f".format(mb / 1000.0)} GB, best on Wi-Fi.")
                list.forEach { modelRow(it) }
                body.addView(ui.space(8))
                body.addView(ui.hint("Picked for this phone. You can change models later in Settings."))
                primary.text = "Download"; secondary.text = "Later"
                refreshModels()
            }
            Step.ENABLE -> {
                header("Turn on the Hearsay keyboard", "In the list that opens, switch on Hearsay voice, then come back here.")
                bullet(R.drawable.ic_lock, "Android warns that any keyboard could collect what you type. " +
                    "Hearsay never sends your typing anywhere; it only inserts what you dictate.")
                primary.text = "Open keyboard settings"; secondary.text = "Not now"
            }
            Step.SWITCH -> {
                header("Switch to Hearsay", "Pick Hearsay voice as your keyboard. You can switch back to your regular " +
                    "keyboard anytime with the keyboard key on Hearsay.")
                primary.text = "Choose keyboard"; secondary.text = "Not now"
            }
            Step.TRY -> {
                header("You're all set", "Tap the box, then hold the mic and say something like " +
                    "\"um so I'll be there at five, actually six.\" Let go and watch it clean up.")
                val (box, _) = ui.field("", "Try it here", multiline = 3)
                body.addView(box)
                body.addView(ui.space(12))
                body.addView(ui.hint("Tip: tap the mic once for hands-free. It stops when you pause."))
                primary.text = "Done"; secondary.visibility = View.GONE
            }
        }
    }

    private fun header(t: String, sub: String, center: Boolean = false) {
        body.addView(ui.space(if (center) 24 else 8))
        body.addView(ui.title(t, 28f).apply { if (center) gravity = Gravity.CENTER })
        body.addView(ui.space(12))
        body.addView(ui.body(sub, 16f).apply { setTextColor(ui.onSurfaceVariant); if (center) gravity = Gravity.CENTER })
        body.addView(ui.space(24))
    }

    private fun bullet(icon: Int, t: String) {
        body.addView(ui.row().apply {
            gravity = Gravity.TOP
            addView(ui.icon(icon, ui.primary, 20))
            addView(ui.hint(t).apply { setPadding(ui.dp(12), 0, 0, 0) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun modelRow(m: ModelManager.Model) {
        val label = ui.body("").apply { textSize = 16f }
        val bar = LinearProgressIndicator(this).apply { max = 100; trackCornerRadius = ui.dp(4) }
        labels[m] = label; bars[m] = bar
        body.addView(ui.card(
            ui.hint(if (m.kind == ModelManager.Kind.SPEECH) "Speech recognition" else "Cleanup"),
            label, ui.space(10), bar,
        ))
    }

    private fun refreshModels() {
        for ((m, label) in labels) {
            val ready = models.isReady(m)
            label.text = "${m.shortName} · ${if (ready) "ready" else "~${m.approxMb} MB"}"
            // Bars appear only while downloading; an empty track reads as a full bar.
            bars[m]?.visibility = if (downloading && !ready) View.VISIBLE else View.GONE
        }
        if (step == Step.MODELS) {
            primary.isEnabled = !downloading
            primary.text = when { downloading -> "Downloading…"; modelsOk() -> "Next"; else -> "Download" }
        }
    }

    // ------------------------------------------------------------------ actions

    private fun onPrimary() {
        when (step) {
            Step.WELCOME -> show(Step.entries.drop(1).firstOrNull { !done(it) } ?: Step.TRY, auto = true)
            Step.MIC -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            Step.MODELS -> if (modelsOk()) advanceIfDone() else downloadAll()
            Step.ENABLE -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            Step.SWITCH -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            Step.TRY -> finishSetup()
        }
    }

    private fun onSecondary() {
        if (step == Step.WELCOME) { finishSetup(); return }
        val next = Step.entries.getOrNull(step.ordinal + 1) ?: return finishSetup()
        show(next, auto = true)
    }

    private fun downloadAll() {
        if (downloading) return
        downloading = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshModels()
        lifecycleScope.launch {
            val todo = listOfNotNull(models.selected, models.selectedCleanup).filter { !models.isReady(it) }
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    for (m in todo) models.download(m) { pct -> runOnUiThread { bars[m]?.setProgressCompat(pct, true) } }
                }.exceptionOrNull()
            }
            downloading = false
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            refreshModels()
            if (failure != null) {
                android.widget.Toast.makeText(this@SetupActivity, "Download failed: ${failure.message}. Tap Download to resume.",
                    android.widget.Toast.LENGTH_LONG).show()
            } else advanceIfDone()
        }
    }

    private fun finishSetup() {
        Prefs.setSetupDone(this)
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
