package dev.offlineflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.offlineflow.cleanup.Dictionary
import dev.offlineflow.cleanup.Snippets
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-run setup: mic permission, model choice + download, enable + select the keyboard. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var models: ModelManager
    private lateinit var info: TextView
    private lateinit var speechProgress: ProgressBar
    private lateinit var speechButton: Button
    private lateinit var cleanupProgress: ProgressBar
    private lateinit var cleanupButton: Button
    private val downloading = mutableSetOf<ModelManager.Model>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = ModelManager(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        info = TextView(this).apply { textSize = 16f }
        fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        speechProgress = bar(); cleanupProgress = bar()

        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
        fun heading(t: String) = TextView(this).apply { text = t; textSize = 15f }

        val speechModels = ModelManager.Model.entries.filter { it.kind == ModelManager.Kind.SPEECH }
        val cleanupModels = ModelManager.Model.entries.filter { it.kind == ModelManager.Kind.CLEANUP }

        speechButton = button("") { download(models.selected, speechButton, speechProgress) }
        cleanupButton = button("") { models.selectedCleanup?.let { download(it, cleanupButton, cleanupProgress) } }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = "Hearsay"; textSize = 28f })
            addView(TextView(context).apply { text = "Voice dictation that never leaves your phone."; textSize = 15f })
            addView(info)
            addView(button("1. Allow microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) })

            addView(heading("\nSpeech model"))
            addView(RadioGroup(context).apply {
                speechModels.forEachIndexed { i, m ->
                    val rec = if (m == models.recommended) " · recommended for this phone" else ""
                    addView(RadioButton(context).apply {
                        id = 1000 + i
                        text = "${m.label} (~${m.approxMb} MB)$rec"
                        isChecked = m == models.selected
                    })
                }
                setOnCheckedChangeListener { _, id -> models.selected = speechModels[id - 1000]; refresh() }
            })
            addView(speechButton); addView(speechProgress)

            addView(heading("\nCleanup (removes fillers, applies corrections, fixes punctuation)"))
            addView(RadioGroup(context).apply {
                cleanupModels.forEachIndexed { i, m ->
                    val rec = if (m == models.recommendedCleanup) " · recommended for this phone" else ""
                    addView(RadioButton(context).apply {
                        id = 2000 + i
                        text = "${m.label} (~${m.approxMb} MB)$rec"
                        isChecked = m == models.selectedCleanup
                    })
                }
                addView(RadioButton(context).apply {
                    id = 2999; text = "Off · basic rules only"; isChecked = models.selectedCleanup == null
                })
                setOnCheckedChangeListener { _, id ->
                    models.selectedCleanup = if (id == 2999) null else cleanupModels[id - 2000]; refresh()
                }
            })
            addView(cleanupButton); addView(cleanupProgress)

            addView(heading("\nPersonal dictionary"))
            addView(TextView(context).apply {
                textSize = 13f
                text = "One per line. Names and jargon get spelled exactly as you write them. " +
                    "To fix something it keeps mishearing, write what it hears, then -> and what you want, e.g. hear say -> Hearsay"
            })
            val dict = EditText(context).apply {
                setText(Dictionary.text(context))
                hint = "Hearsay\nGitHub\nhear say -> Hearsay"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 4; maxLines = 12
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            addView(dict)
            addView(button("Save dictionary") {
                Dictionary.save(this@SettingsActivity, dict.text.toString())
                val d = Dictionary.load(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "Saved ${d.terms.size} words, ${d.fixes.size} fixes", Toast.LENGTH_SHORT).show()
            })

            addView(heading("\nSnippets"))
            addView(TextView(context).apply {
                textSize = 13f
                text = "One per line: what you say = what gets typed. Say just the phrase and Hearsay inserts the text exactly. " +
                    "Use \\n for a line break, e.g. my address = 1 Main St\\nSan Jose, CA"
            })
            val snips = EditText(context).apply {
                setText(Snippets.text(context))
                hint = "my email = you@example.com\nmy address = 1 Main St\\nSan Jose, CA"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 3; maxLines = 12
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            addView(snips)
            addView(button("Save snippets") {
                Snippets.save(this@SettingsActivity, snips.text.toString())
                val n = Snippets.load(this@SettingsActivity).entries.size
                Toast.makeText(this@SettingsActivity, "Saved $n snippets", Toast.LENGTH_SHORT).show()
            })

            addView(heading(""))
            addView(button("3. Enable keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
            addView(button("4. Switch to Hearsay") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); refresh()
    }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val s = models.selected
        val c = models.selectedCleanup
        val cleanupLine = when {
            c == null -> "Cleanup: basic rules"
            models.isReady(c) -> "Cleanup: ${c.shortName} ✓ ready"
            else -> "Cleanup: ${c.shortName} not downloaded (basic rules until then)"
        }
        info.text = "\nMicrophone: ${if (mic) "✓" else "needed"}\n" +
            "Speech: ${s.shortName} ${if (models.isReady(s)) "✓ ready" else "not downloaded"}\n$cleanupLine\n"
        show(s, speechButton, speechProgress, "2. ")
        if (c == null) {
            cleanupButton.isEnabled = false; cleanupButton.text = "Cleanup model not needed"; cleanupProgress.progress = 0
        } else {
            cleanupButton.isEnabled = true; show(c, cleanupButton, cleanupProgress, "2b. ")
        }
    }

    private fun show(m: ModelManager.Model, button: Button, bar: ProgressBar, step: String) {
        if (m in downloading) return
        val ready = models.isReady(m)
        button.text = if (ready) "$step${m.shortName} downloaded ✓" else "${step}Download ${m.shortName} (~${m.approxMb} MB)"
        bar.progress = if (ready) 100 else 0
    }

    private fun download(m: ModelManager.Model, button: Button, bar: ProgressBar) {
        if (m in downloading || models.isReady(m)) return
        downloading += m
        button.text = "Downloading ${m.shortName}… keep this screen open"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { models.download(m) { pct -> runOnUiThread { bar.progress = pct } } }
            }
            downloading -= m
            refresh()
            result.onFailure { info.text = "\nDownload failed: ${it.message}\n" }
        }
    }
}
