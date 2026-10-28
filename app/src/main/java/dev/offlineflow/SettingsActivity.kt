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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.offlineflow.ime.OfflineFlowIME
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-run setup: mic permission, model download, enable + select the keyboard. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var models: ModelManager
    private lateinit var info: TextView
    private lateinit var progress: ProgressBar

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
            addView(button("2. Download speech model (~${OfflineFlowIME.MODEL.approxMb} MB)") { download() })
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
        val model = models.isReady(OfflineFlowIME.MODEL)
        info.text = "\nMicrophone: ${if (mic) "✓" else "needed"}\nModel: ${if (model) "✓ ready" else "not downloaded"}\n"
        if (model) progress.progress = 100
    }

    private fun download() {
        if (models.isReady(OfflineFlowIME.MODEL)) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    models.download(OfflineFlowIME.MODEL) { pct -> runOnUiThread { progress.progress = pct } }
                }
            }
            result.onFailure { info.text = "\nDownload failed: ${it.message}\n" }
            refresh()
        }
    }
}
