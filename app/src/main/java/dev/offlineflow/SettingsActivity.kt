package dev.offlineflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-run setup: mic permission, model choice + download, enable + select the keyboard. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var models: ModelManager
    private lateinit var info: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadButton: Button
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = ModelManager(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        info = TextView(this).apply { textSize = 16f }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }

        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = "Hearsay"; textSize = 28f })
            addView(TextView(context).apply { text = "Voice dictation that never leaves your phone."; textSize = 15f })
            addView(info)
            addView(button("1. Allow microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) })
            addView(TextView(context).apply { text = "\nSpeech model"; textSize = 15f })
            addView(RadioGroup(context).apply {
                ModelManager.Model.entries.forEachIndexed { i, m ->
                    val rec = if (m == models.recommended) " · recommended for this phone" else ""
                    addView(RadioButton(context).apply {
                        id = 1000 + i
                        text = "${m.label} (~${m.approxMb} MB)$rec"
                        isChecked = m == models.selected
                    })
                }
                setOnCheckedChangeListener { _, checkedId ->
                    models.selected = ModelManager.Model.entries[checkedId - 1000]; refresh()
                }
            })
            downloadButton = button("2. Download speech model") { download() }
            addView(downloadButton)
            addView(progress)
            addView(button("3. Enable keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
            addView(button("4. Switch to Hearsay") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
        })
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); refresh()
    }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val m = models.selected
        val model = models.isReady(m)
        info.text = "\nMicrophone: ${if (mic) "✓" else "needed"}\n${m.shortName}: ${if (model) "✓ ready" else "not downloaded"}\n"
        if (!downloading) downloadButton.text = if (model) "2. ${m.shortName} downloaded ✓" else "2. Download ${m.shortName} (~${m.approxMb} MB)"
        if (!downloading) progress.progress = if (model) 100 else 0
    }

    private fun download() {
        val m = models.selected
        if (downloading || models.isReady(m)) return
        downloading = true
        downloadButton.text = "Downloading ${m.shortName}… keep this screen open"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    models.download(m) { pct -> runOnUiThread { progress.progress = pct } }
                }
            }
            downloading = false
            refresh()
            result.onFailure { info.text = "\nDownload failed: ${it.message}\n" }
        }
    }
}
