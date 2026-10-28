package dev.offlineflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.offlineflow.cleanup.Dictionary
import dev.offlineflow.cleanup.Snippets
import dev.offlineflow.cloud.ChatGptPlan
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup and settings, as a column of cards: setup checklist, models, hands-free, styles,
 * dictionary, snippets, storage. Everything is stored on the phone.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var models: ModelManager
    private lateinit var info: TextView
    private lateinit var micButton: Button
    private lateinit var enableButton: Button
    private lateinit var switchButton: Button
    private lateinit var speechProgress: ProgressBar
    private lateinit var speechButton: Button
    private lateinit var cleanupProgress: ProgressBar
    private lateinit var cleanupButton: Button
    private lateinit var stylesInfo: TextView
    private lateinit var stylesReset: Button
    private lateinit var storageBox: LinearLayout
    private var planBox: LinearLayout? = null
    private var signingIn = false
    private val downloading = mutableSetOf<ModelManager.Model>()
    private var dp = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = ModelManager(this)
        dp = resources.displayMetrics.density
        fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }

        val speechModels = ModelManager.Model.entries.filter { it.kind == ModelManager.Kind.SPEECH }
        val cleanupModels = ModelManager.Model.entries.filter { it.kind == ModelManager.Kind.CLEANUP }

        info = TextView(this).apply { textSize = 14f }
        micButton = button("") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        enableButton = button("") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        switchButton = button("") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        speechProgress = bar(); cleanupProgress = bar()
        speechButton = button("") { download(models.selected, speechButton, speechProgress) }
        cleanupButton = button("") { models.selectedCleanup?.let { download(it, cleanupButton, cleanupProgress) } }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, (24 * dp).toInt(), pad, (32 * dp).toInt())
            addView(TextView(context).apply { text = "Hearsay"; textSize = 28f; setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(context).apply { text = "Voice dictation that never leaves your phone."; textSize = 15f })

            addView(card("Setup",
                info, micButton, speechButton, speechProgress, enableButton, switchButton,
                hint("Try it here once the keyboard is on:"),
                EditText(context).apply { this.hint = "Tap here, then hold the mic and talk"; minLines = 2 },
            ))

            addView(card("Speech model",
                RadioGroup(context).apply {
                    speechModels.forEachIndexed { i, m ->
                        val rec = if (m == models.recommended) " · recommended for this phone" else ""
                        addView(RadioButton(context).apply {
                            id = 1000 + i; text = "${m.label} (~${m.approxMb} MB)$rec"; isChecked = m == models.selected
                        })
                    }
                    setOnCheckedChangeListener { _, id -> models.selected = speechModels[id - 1000]; refresh() }
                },
            ))

            addView(card("Cleanup",
                hint("Removes fillers, applies your corrections, fixes punctuation. Runs on the phone."),
                RadioGroup(context).apply {
                    cleanupModels.forEachIndexed { i, m ->
                        val rec = if (m == models.recommendedCleanup) " · recommended for this phone" else ""
                        addView(RadioButton(context).apply {
                            id = 2000 + i; text = "${m.label} (~${m.approxMb} MB)$rec"; isChecked = m == models.selectedCleanup
                        })
                    }
                    addView(RadioButton(context).apply {
                        id = 2999; text = "Off · basic rules only"; isChecked = models.selectedCleanup == null
                    })
                    setOnCheckedChangeListener { _, id ->
                        models.selectedCleanup = if (id == 2999) null else cleanupModels[id - 2000]; refresh()
                    }
                },
                cleanupButton, cleanupProgress,
            ))

            addView(card("Talking",
                hint("Hold the mic to talk, or tap it once for hands-free. Hands-free stops after a pause this long:"),
                RadioGroup(context).apply {
                    val current = Prefs.pauseMs(context)
                    Prefs.PAUSES.forEachIndexed { i, (ms, label) ->
                        addView(RadioButton(context).apply { id = 3000 + i; text = label; isChecked = ms == current })
                    }
                    setOnCheckedChangeListener { _, id -> Prefs.setPauseMs(context, Prefs.PAUSES[id - 3000].first) }
                },
                CheckBox(context).apply {
                    text = "Vibrate when the mic starts and stops"; isChecked = Prefs.haptics(context)
                    setOnCheckedChangeListener { _, on -> Prefs.setHaptics(context, on) }
                },
            ))

            stylesInfo = hint("")
            stylesReset = button("Reset all apps to defaults") {
                Prefs.resetStyleOverrides(this@SettingsActivity); refresh()
                toast("Styles reset")
            }
            addView(card("Writing styles",
                hint("Casual for messaging, Polished for email and work apps, Verbatim for code, links and search " +
                    "(no AI rewrite). Tap the style button on the keyboard to change it for that app."),
                stylesInfo, stylesReset,
            ))

            addView(card("Commands",
                hint("Select text and say what to change: \"make this shorter\", \"more formal\", \"turn it into bullets\", " +
                    "\"fix the grammar\". Say anything else to replace the selection. With nothing selected, " +
                    "\"make that more formal\" edits what you just dictated, and \"scratch that\" deletes it. " +
                    "Raw undoes an edit. Needs a cleanup model."),
            ))

            val dict = editor(Dictionary.text(context), "Hearsay\nGitHub\nhear say -> Hearsay", 4)
            addView(card("Personal dictionary",
                hint("One per line. Names and jargon get spelled exactly as you write them. To fix something it keeps " +
                    "mishearing, write what it hears, then -> and what you want, e.g. hear say -> Hearsay"),
                dict,
                button("Save dictionary") {
                    Dictionary.save(this@SettingsActivity, dict.text.toString())
                    val d = Dictionary.load(this@SettingsActivity)
                    toast("Saved ${d.terms.size} words, ${d.fixes.size} fixes")
                },
            ))

            val snips = editor(Snippets.text(context), "my email = you@example.com\nmy address = 1 Main St\\nSan Jose, CA", 3)
            addView(card("Snippets",
                hint("One per line: what you say = what gets typed. Say just the phrase and Hearsay inserts the text " +
                    "exactly. Use \\n for a line break."),
                snips,
                button("Save snippets") {
                    Snippets.save(this@SettingsActivity, snips.text.toString())
                    toast("Saved ${Snippets.load(this@SettingsActivity).entries.size} snippets")
                },
            ))

            storageBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(card("Storage", hint("Downloaded models. Remove ones you don't use to free space."), storageBox))

            if (BuildConfig.CLOUD) {
                planBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(card("Use your ChatGPT plan (optional)",
                    hint("Run cleanup and commands on GPT-6 Luna with your ChatGPT plan instead of the on-device model. " +
                        "No API key needed. Speech is still transcribed on this phone; only the transcribed text is sent to OpenAI. " +
                        "If ChatGPT can't be reached, Hearsay falls back to the on-device model."),
                    planBox!!,
                ))

                fun field(value: String, hintText: String, secret: Boolean = false) = EditText(context).apply {
                    setText(value); hint = hintText; isSingleLine = true
                    inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                val url = field(Prefs.cloudUrl(context), "https://api.openai.com/v1")
                val key = field(Prefs.cloudKey(context), "API key", secret = true)
                val asrModel = field(Prefs.cloudSpeechModel(context), "Speech model, e.g. whisper-1")
                val llmModel = field(Prefs.cloudCleanupModel(context), "Cleanup model, e.g. gpt-4o-mini")
                val speech = CheckBox(context).apply { text = "Use the cloud for speech"; isChecked = Prefs.cloudSpeech(context) }
                val cleanup = CheckBox(context).apply { text = "Use the cloud for cleanup and commands"; isChecked = Prefs.cloudCleanup(context) }
                addView(card("Cloud mode with your own API key (optional)",
                    hint("Use your own key for an OpenAI-compatible service instead of the on-device models. " +
                        "When on, your audio or text is sent to that service. Leave both boxes unticked to stay fully offline. " +
                        "Needs an https address."),
                    url, key, asrModel, llmModel, speech, cleanup,
                    button("Save cloud settings") {
                        val u = url.text.toString().trim()
                        if ((speech.isChecked || cleanup.isChecked) && !u.startsWith("https://")) {
                            toast("The address must start with https://"); return@button
                        }
                        if ((speech.isChecked || cleanup.isChecked) && key.text.isBlank()) {
                            toast("Add your API key first"); return@button
                        }
                        Prefs.saveCloud(this@SettingsActivity, u, key.text.toString(), asrModel.text.toString(),
                            llmModel.text.toString(), speech.isChecked, cleanup.isChecked)
                        toast(if (speech.isChecked || cleanup.isChecked) "Cloud mode on" else "Saved · fully offline")
                    },
                ))
            }

            addView(hint("\nAudio stays on this phone unless you turn on cloud speech. Text stays here too unless you use your ChatGPT plan or cloud mode. Otherwise the only network use is downloading models.").apply {
                gravity = Gravity.CENTER
            })
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh() // the keyboard picker is a dialog, so no onResume after it
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); refresh()
    }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val active = current?.startsWith("$packageName/") == true
        val s = models.selected
        val c = models.selectedCleanup
        val speechReady = models.isReady(s)
        val allSet = mic && speechReady && enabled && active

        info.text = if (allSet) "✓ All set. Open any app and use the mic." else "Finish these steps to start dictating."
        micButton.text = if (mic) "✓ Microphone allowed" else "1. Allow microphone"
        micButton.isEnabled = !mic
        enableButton.text = if (enabled) "✓ Keyboard enabled" else "3. Enable Hearsay keyboard"
        switchButton.text = if (active) "✓ Hearsay is your current keyboard" else "4. Switch to Hearsay"
        show(s, speechButton, speechProgress, "2. ")
        if (c == null) {
            cleanupButton.isEnabled = false; cleanupButton.text = "Using basic rules"; cleanupProgress.visibility = View.GONE
        } else {
            cleanupButton.isEnabled = true; cleanupProgress.visibility = View.VISIBLE; show(c, cleanupButton, cleanupProgress, "")
        }

        val n = Prefs.styleOverrides(this)
        stylesInfo.text = if (n == 0) "All apps use their default style." else "You've picked a style in $n app${if (n == 1) "" else "s"}."
        stylesReset.visibility = if (n == 0) View.GONE else View.VISIBLE

        refreshStorage()
        refreshPlan()
    }

    /** The ChatGPT plan card: Continue with ChatGPT, or the signed-in account and its controls. */
    private fun refreshPlan() {
        val box = planBox ?: return
        box.removeAllViews()
        val account = ChatGptPlan.account(this)
        if (account == null) {
            box.addView(Button(this).apply {
                text = if (signingIn) "Waiting for ChatGPT sign-in…" else "Continue with ChatGPT"
                isAllCaps = false; isEnabled = !signingIn
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = 24 * dp; setColor(Color.BLACK) }
                setOnClickListener { signInWithChatGpt() }
            })
            return
        }
        val on = Prefs.chatGptCleanup(this)
        box.addView(TextView(this).apply {
            textSize = 14f
            text = "Signed in as ${account.email.ifEmpty { "your ChatGPT account" }}" + if (on) "\nUsing ChatGPT plan · ${Prefs.chatGptModel(this@SettingsActivity)}" else ""
        })
        box.addView(CheckBox(this).apply {
            text = "Use my ChatGPT plan for cleanup and commands"; isChecked = on
            setOnCheckedChangeListener { _, checked -> Prefs.setChatGptCleanup(this@SettingsActivity, checked); refreshPlan() }
        })
        val model = EditText(this).apply {
            setText(Prefs.chatGptModel(this@SettingsActivity)); hint = ChatGptPlan.DEFAULT_MODEL; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(model)
        box.addView(button("Save model") { Prefs.setChatGptModel(this, model.text.toString()); toast("Model saved"); refreshPlan() })
        box.addView(button("Manage usage") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ChatGptPlan.USAGE_URL))) })
        box.addView(button("Sign out") {
            lifecycleScope.launch {
                val revoked = withContext(Dispatchers.IO) { ChatGptPlan.signOut(this@SettingsActivity) }
                toast(if (revoked) "Signed out" else "Signed out here. You can also disconnect Hearsay in ChatGPT settings.")
                refreshPlan()
            }
        })
    }

    private fun signInWithChatGpt() {
        if (signingIn) return
        signingIn = true; refreshPlan()
        // App-wide scope: the activity sits in the background while the browser is open.
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val result = runCatching { ChatGptPlan.signIn(this@SettingsActivity) }
            runOnUiThread {
                signingIn = false
                result.onFailure { toast(it.message ?: "Sign-in failed") }
                result.onSuccess {
                    if (!Prefs.chatGptWelcomed(this@SettingsActivity)) {
                        Prefs.setChatGptWelcomed(this@SettingsActivity)
                        if (!isFinishing) AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("You're using your ChatGPT plan")
                            .setMessage("Cleanup and commands in Hearsay now use your ChatGPT plan. You can manage usage in ChatGPT settings.")
                            .setPositiveButton("Got it", null)
                            .show()
                    }
                }
                refreshPlan()
            }
        }
    }

    private fun refreshStorage() {
        storageBox.removeAllViews()
        val inUse = setOfNotNull(models.selected, models.selectedCleanup)
        val onDisk = ModelManager.Model.entries.filter { it !in downloading && models.bytesOnDisk(it) > 0 }
        if (onDisk.isEmpty()) { storageBox.addView(hint("Nothing downloaded yet.")); return }
        for (m in onDisk) {
            val mb = models.bytesOnDisk(m) / 1_000_000
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val partial = if (models.isReady(m)) "" else " · partial"
            row.addView(TextView(this).apply {
                textSize = 14f; text = "${m.shortName} · $mb MB$partial" + if (m in inUse) " · in use" else ""
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (m !in inUse) row.addView(button("Remove") {
                AlertDialog.Builder(this)
                    .setMessage("Remove ${m.shortName}? You can download it again later.")
                    .setPositiveButton("Remove") { _, _ -> models.delete(m); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            storageBox.addView(row)
        }
    }

    private fun show(m: ModelManager.Model, button: Button, bar: ProgressBar, step: String) {
        if (m in downloading) return
        val ready = models.isReady(m)
        button.text = if (ready) "✓ ${m.shortName} downloaded" else "${step}Download ${m.shortName} (~${m.approxMb} MB)"
        button.isEnabled = !ready
        bar.progress = if (ready) 100 else 0
        bar.visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun download(m: ModelManager.Model, button: Button, bar: ProgressBar) {
        if (m in downloading || models.isReady(m)) return
        downloading += m
        button.text = "Downloading ${m.shortName}… keep this screen open"
        button.isEnabled = false
        bar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { models.download(m) { pct -> runOnUiThread { bar.progress = pct } } }
            }
            downloading -= m
            refresh()
            result.onFailure { toast("Download failed: ${it.message}") }
        }
    }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }

    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 13f; alpha = 0.8f }

    private fun editor(value: String, hintText: String, lines: Int) = EditText(this).apply {
        setText(value)
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = lines; maxLines = 12
        gravity = Gravity.TOP or Gravity.START
    }

    private fun card(title: String, vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (14 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.parseColor("#1A808080")) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (16 * dp).toInt() }
        addView(TextView(context).apply { text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD) })
        children.forEach { addView(it) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
