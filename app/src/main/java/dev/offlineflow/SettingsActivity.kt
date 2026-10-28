package dev.offlineflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import dev.offlineflow.cleanup.Dictionary
import dev.offlineflow.cleanup.Snippets
import dev.offlineflow.cloud.ChatGptPlan
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home screen and settings: a status card up top, then grouped cards (ChatGPT plan, voice,
 * cleanup, your words, storage, advanced). First launch goes through [SetupActivity].
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var models: ModelManager
    private lateinit var statusBox: LinearLayout
    private lateinit var speechAction: MaterialButton
    private lateinit var speechBar: LinearProgressIndicator
    private lateinit var cleanupAction: MaterialButton
    private lateinit var cleanupBar: LinearProgressIndicator
    private lateinit var stylesInfo: TextView
    private lateinit var stylesReset: MaterialButton
    private lateinit var storageBox: LinearLayout
    private var planBox: LinearLayout? = null
    private var signingIn = false
    private val downloading = mutableSetOf<ModelManager.Model>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        models = ModelManager(this)
        if (savedInstanceState == null && !Prefs.setupDone(this) && !allSet()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        val speechModels = ModelManager.Model.entries.filter { it.kind == ModelManager.Kind.SPEECH }
        val cleanupModels = ModelManager.Model.entries.filter { it.kind == ModelManager.Kind.CLEANUP }
        fun bar() = LinearProgressIndicator(this).apply { max = 100; trackCornerRadius = ui.dp(4) }
        speechBar = bar(); cleanupBar = bar()
        speechAction = ui.tonal("") { download(models.selected, speechAction, speechBar) }
        cleanupAction = ui.tonal("") { models.selectedCleanup?.let { download(it, cleanupAction, cleanupBar) } }

        val content = ui.column(ui.dp(16), 0).apply {
            setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(32))

            addView(ui.row().apply {
                addView(ImageView(context).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
                addView(ui.column().apply {
                    setPadding(ui.dp(12), 0, 0, 0)
                    addView(ui.title("Hearsay", 26f))
                    addView(ui.hint("Talk. It types."))
                })
            })
            addView(ui.space(20))

            statusBox = ui.column()
            addView(ui.card(statusBox))

            if (BuildConfig.CLOUD) {
                addView(ui.overline("ChatGPT"))
                planBox = ui.column()
                addView(ui.card(
                    ui.title("Use your ChatGPT plan", 18f),
                    ui.space(4),
                    ui.hint("Cleanup and commands run on GPT-6 Luna with your plan. No API key. Speech is still transcribed " +
                        "on this phone; only the text is sent. Offline or out of usage? Hearsay falls back to on-device."),
                    ui.space(14),
                    planBox!!,
                ))
            }

            addView(ui.overline("Voice"))
            addView(ui.card(
                ui.title("Speech model", 18f),
                ui.hint("Runs on this phone."),
                RadioGroup(context).apply {
                    speechModels.forEachIndexed { i, m ->
                        addView(radio(1000 + i, m.label, "~${m.approxMb} MB" + if (m == models.recommended) " · best for this phone" else "", m == models.selected))
                    }
                    setOnCheckedChangeListener { _, id -> models.selected = speechModels[id - 1000]; refresh() }
                },
                speechBar, speechAction,
            ))
            addView(ui.card(
                ui.title("Talking", 18f),
                ui.hint("Hold the mic to talk, or tap once for hands-free. Hands-free stops after you pause for:"),
                ui.space(10),
                MaterialButtonToggleGroup(context).apply {
                    isSingleSelection = true; isSelectionRequired = true
                    val current = Prefs.pauseMs(context)
                    val ids = Prefs.PAUSES.map { (ms, label) ->
                        val b = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                            id = View.generateViewId(); text = label.substringAfter("· "); isAllCaps = false
                        }
                        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        if (ms == current) check(b.id)
                        b.id to ms
                    }.toMap()
                    addOnButtonCheckedListener { _, id, checked -> if (checked) ids[id]?.let { Prefs.setPauseMs(context, it) } }
                },
                switch("Vibrate when the mic starts and stops", Prefs.haptics(context)) { Prefs.setHaptics(context, it) },
            ))

            addView(ui.overline("Cleanup"))
            addView(ui.card(
                ui.title("Cleanup model", 18f),
                ui.hint("Removes fillers, fixes punctuation, applies your words. Used when the ChatGPT plan is off or unreachable."),
                RadioGroup(context).apply {
                    cleanupModels.forEachIndexed { i, m ->
                        addView(radio(2000 + i, m.label, "~${m.approxMb} MB" + if (m == models.recommendedCleanup) " · best for this phone" else "", m == models.selectedCleanup))
                    }
                    addView(radio(2999, "Off", "Basic rules only", models.selectedCleanup == null))
                    setOnCheckedChangeListener { _, id ->
                        models.selectedCleanup = if (id == 2999) null else cleanupModels[id - 2000]; refresh()
                    }
                },
                cleanupBar, cleanupAction,
            ))
            stylesInfo = ui.hint("")
            stylesReset = ui.text("Reset all apps to defaults") {
                Prefs.resetStyleOverrides(this@SettingsActivity); refresh(); toast("Styles reset")
            }
            addView(ui.card(
                ui.title("Writing styles", 18f),
                ui.hint("Casual for messaging, Polished for email and work, Verbatim for code, links and search (no rewrite). " +
                    "Tap the style chip on the keyboard to change it for that app."),
                ui.space(8), stylesInfo, stylesReset,
            ))
            addView(ui.card(
                ui.title("Voice commands", 18f),
                ui.space(4),
                example("Select text, then say", "\"make this shorter\" · \"more formal\" · \"turn it into bullets\""),
                example("Right after dictating", "\"make that friendlier\" · \"scratch that\""),
                ui.space(6),
                ui.hint("The undo key puts back the original."),
            ))

            addView(ui.overline("Your words"))
            val (dictBox, dict) = ui.field(Dictionary.text(context), "Words and fixes", multiline = 4)
            addView(ui.card(
                ui.title("Personal dictionary", 18f),
                ui.hint("One per line. Names and jargon are spelled exactly as written. Fix a repeat mishearing with " +
                    "what it hears -> what you want, e.g. hear say -> Hearsay"),
                dictBox,
                ui.tonal("Save dictionary") {
                    Dictionary.save(this@SettingsActivity, dict.text.toString())
                    val d = Dictionary.load(this@SettingsActivity)
                    toast("Saved ${d.terms.size} words, ${d.fixes.size} fixes")
                }.withTopMargin(),
            ))
            val (snipBox, snips) = ui.field(Snippets.text(context), "Snippets", multiline = 3)
            addView(ui.card(
                ui.title("Snippets", 18f),
                ui.hint("One per line: what you say = what gets typed, e.g. my email = you@example.com. Use \\n for a new line."),
                snipBox,
                ui.tonal("Save snippets") {
                    Snippets.save(this@SettingsActivity, snips.text.toString())
                    toast("Saved ${Snippets.load(this@SettingsActivity).entries.size} snippets")
                }.withTopMargin(),
            ))

            addView(ui.overline("Storage"))
            storageBox = ui.column()
            addView(ui.card(ui.hint("Models on this phone."), ui.space(4), storageBox))

            if (BuildConfig.CLOUD) {
                addView(ui.overline("Advanced"))
                addView(advancedCloudCard())
            }

            addView(ui.space(24))
            addView(ui.row().apply {
                gravity = Gravity.CENTER
                addView(ui.icon(R.drawable.ic_lock, ui.onSurfaceVariant, 16))
                addView(ui.hint("  Audio never leaves this phone unless you turn on cloud speech."))
            })
            addView(ui.hint("Version ${packageManager.getPackageInfo(packageName, 0).versionName}").apply {
                gravity = Gravity.CENTER; textSize = 12f; setPadding(0, ui.dp(6), 0, 0)
            })
        }
        val scroll = ScrollView(this).apply { clipToPadding = false; addView(content) }
        val frame = android.widget.FrameLayout(this).apply { addView(scroll) }
        Ui.edgeToEdgeScroll(this, frame, scroll)
        setContentView(frame)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); refresh() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    // ------------------------------------------------------------------ state

    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun enabledOk() = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
    private fun currentOk() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
    /** Ready once the keyboard can dictate; being the current keyboard right now isn't required. */
    private fun allSet() = micOk() && models.isReady(models.selected) && enabledOk()

    private fun refresh() {
        refreshStatus()
        show(models.selected, speechAction, speechBar)
        val c = models.selectedCleanup
        if (c == null) { cleanupAction.visibility = View.GONE; cleanupBar.visibility = View.GONE }
        else show(c, cleanupAction, cleanupBar)

        val n = Prefs.styleOverrides(this)
        stylesInfo.text = if (n == 0) "Every app uses its default style." else "You've picked a style in $n app${if (n == 1) "" else "s"}."
        stylesReset.visibility = if (n == 0) View.GONE else View.VISIBLE
        refreshStorage()
        refreshPlan()
    }

    private fun refreshStatus() {
        statusBox.removeAllViews()
        if (!allSet()) {
            val next = when {
                !micOk() -> "Next: allow the microphone."
                !models.isReady(models.selected) -> "Next: download the speech model."
                else -> "Next: turn on the Hearsay keyboard."
            }
            statusBox.addView(ui.title("Finish setting up", 18f))
            statusBox.addView(ui.hint(next))
            statusBox.addView(ui.filled("Continue setup") { startActivity(Intent(this, SetupActivity::class.java)) }.withTopMargin(14))
            return
        }
        val cleanup = when {
            BuildConfig.CLOUD && ChatGptPlan.account(this) != null && Prefs.chatGptCleanup(this) -> "${Prefs.chatGptModel(this)} via ChatGPT"
            models.selectedCleanup?.let { models.isReady(it) } == true -> models.selectedCleanup!!.shortName
            else -> "basic rules"
        }
        statusBox.addView(ui.row().apply {
            addView(ui.icon(R.drawable.ic_check, ui.primary, 28))
            addView(ui.column().apply {
                setPadding(ui.dp(14), 0, 0, 0)
                addView(ui.title("Ready to dictate", 18f))
                addView(ui.hint("${models.selected.shortName} on-device · cleanup: $cleanup"))
            })
        })
        if (!currentOk()) statusBox.addView(ui.tonal("Switch to Hearsay keyboard") {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }.withTopMargin(14))
        val (box, _) = ui.field("", "Try it: tap here and hold the mic", multiline = 2)
        statusBox.addView(box)
    }

    private fun show(m: ModelManager.Model, button: MaterialButton, bar: LinearProgressIndicator) {
        if (m in downloading) return
        val ready = models.isReady(m)
        button.visibility = if (ready) View.GONE else View.VISIBLE
        bar.visibility = View.GONE  // only while downloading; an empty track reads as a full bar
        button.text = "Download ${m.shortName} (~${m.approxMb} MB)"
        button.isEnabled = true
        bar.setProgressCompat(0, false)
    }

    private fun download(m: ModelManager.Model, button: MaterialButton, bar: LinearProgressIndicator) {
        if (m in downloading || models.isReady(m)) return
        downloading += m
        button.text = "Downloading… keep this screen open"; button.isEnabled = false
        bar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { models.download(m) { pct -> runOnUiThread { bar.setProgressCompat(pct, true) } } }
            }
            downloading -= m
            refresh()
            result.onFailure { toast("Download failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ ChatGPT

    private fun refreshPlan() {
        val box = planBox ?: return
        box.removeAllViews()
        val account = ChatGptPlan.account(this)
        if (account == null) {
            if (!signingIn) ChatGptPlan.lastError(this)?.let { err ->
                box.addView(ui.hint("Sign-in didn't finish: $err").apply { setTextColor(ui.error); setPadding(0, 0, 0, ui.dp(10)) })
            }
            box.addView(MaterialButton(this).apply {
                text = if (signingIn) "Waiting for ChatGPT…" else "Continue with ChatGPT"
                isAllCaps = false; isEnabled = !signingIn; minHeight = ui.dp(52); cornerRadius = ui.dp(26)
                val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                backgroundTintList = ColorStateList.valueOf(if (dark) Color.WHITE else Color.BLACK)
                setTextColor(if (dark) Color.BLACK else Color.WHITE)
                setOnClickListener { signInWithChatGpt() }
            })
            return
        }
        val on = Prefs.chatGptCleanup(this)
        box.addView(ui.body(account.email.ifEmpty { "Your ChatGPT account" }).apply { setTypeface(typeface, Typeface.BOLD) })
        box.addView(ui.hint(if (on) "Cleanup and commands use ${Prefs.chatGptModel(this)}" else "Plan not in use · on-device cleanup"))
        box.addView(switch("Use my ChatGPT plan", on) { Prefs.setChatGptCleanup(this, it); refreshPlan(); refreshStatus() })
        val (modelBox, model) = ui.field(Prefs.chatGptModel(this), "Model")
        box.addView(modelBox)
        box.addView(ui.row().apply {
            addView(ui.text("Save model") { Prefs.setChatGptModel(this@SettingsActivity, model.text.toString()); toast("Model saved"); refreshPlan(); refreshStatus() })
            addView(ui.text("Manage usage") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ChatGptPlan.USAGE_URL))) })
            addView(ui.text("Sign out") {
                lifecycleScope.launch {
                    val revoked = withContext(Dispatchers.IO) { ChatGptPlan.signOut(this@SettingsActivity) }
                    toast(if (revoked) "Signed out" else "Signed out here. You can also disconnect Hearsay in ChatGPT settings.")
                    refresh()
                }
            }.apply { setTextColor(ui.error) })
        })
    }

    private fun signInWithChatGpt() {
        if (signingIn) return
        signingIn = true; ChatGptPlan.setLastError(this, null); refreshPlan()
        // App-wide scope: the activity sits in the background while the browser is open.
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val result = runCatching { ChatGptPlan.signIn(this@SettingsActivity) }
            ChatGptPlan.setLastError(this@SettingsActivity, result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
            runOnUiThread {
                signingIn = false
                result.onSuccess {
                    if (!Prefs.chatGptWelcomed(this@SettingsActivity)) {
                        Prefs.setChatGptWelcomed(this@SettingsActivity)
                        if (!isFinishing) MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setTitle("You're using your ChatGPT plan")
                            .setMessage("Cleanup and commands in Hearsay now use your ChatGPT plan. You can manage usage in ChatGPT settings.")
                            .setPositiveButton("Got it", null)
                            .show()
                    }
                }
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ advanced

    private fun advancedCloudCard(): View {
        val details = ui.column().apply { visibility = View.GONE }
        val (urlBox, url) = ui.field(Prefs.cloudUrl(this), "Base URL (https)")
        val (keyBox, key) = ui.field(Prefs.cloudKey(this), "API key", secret = true)
        val (asrBox, asrModel) = ui.field(Prefs.cloudSpeechModel(this), "Speech model")
        val (llmBox, llmModel) = ui.field(Prefs.cloudCleanupModel(this), "Cleanup model")
        var speech = Prefs.cloudSpeech(this); var cleanup = Prefs.cloudCleanup(this)
        details.addView(ui.hint("For any OpenAI-compatible service. When on, your audio or text goes to that service. " +
            "The ChatGPT plan, when signed in, takes priority for cleanup."))
        listOf(urlBox, keyBox, asrBox, llmBox).forEach { details.addView(it) }
        details.addView(switch("Send audio for transcription", speech) { speech = it })
        details.addView(switch("Use for cleanup and commands", cleanup) { cleanup = it })
        details.addView(ui.tonal("Save") {
            val u = url.text.toString().trim()
            if ((speech || cleanup) && !u.startsWith("https://")) { toast("The address must start with https://"); return@tonal }
            if ((speech || cleanup) && key.text.isNullOrBlank()) { toast("Add your API key first"); return@tonal }
            Prefs.saveCloud(this, u, key.text.toString(), asrModel.text.toString(), llmModel.text.toString(), speech, cleanup)
            toast(if (speech || cleanup) "Own-key cloud mode on" else "Saved · fully on-device")
        }.withTopMargin())

        val toggle = ui.text("Show") {}
        toggle.setOnClickListener {
            val open = details.visibility != View.VISIBLE
            details.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Hide" else "Show"
        }
        return ui.card(
            ui.row().apply {
                addView(ui.column().apply {
                    addView(ui.title("Your own API key", 18f))
                    addView(ui.hint(if (Prefs.cloudSpeech(context) || Prefs.cloudCleanup(context)) "On" else "Off · optional"))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(toggle)
            },
            details,
        )
    }

    // ------------------------------------------------------------------ storage

    private fun refreshStorage() {
        storageBox.removeAllViews()
        val inUse = setOfNotNull(models.selected, models.selectedCleanup)
        val onDisk = ModelManager.Model.entries.filter { it !in downloading && models.bytesOnDisk(it) > 0 }
        if (onDisk.isEmpty()) { storageBox.addView(ui.body("Nothing downloaded yet.")); return }
        for (m in onDisk) {
            val mb = models.bytesOnDisk(m) / 1_000_000
            val partial = if (models.isReady(m)) "" else " · partial"
            storageBox.addView(ui.row().apply {
                minimumHeight = ui.dp(48)
                addView(ui.column().apply {
                    addView(ui.body(m.shortName))
                    addView(ui.hint("$mb MB$partial" + if (m in inUse) " · in use" else ""))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (m !in inUse) addView(ui.text("Remove") {
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setMessage("Remove ${m.shortName}? You can download it again later.")
                        .setPositiveButton("Remove") { _, _ -> models.delete(m); refresh() }
                        .setNegativeButton("Cancel", null)
                        .show()
                })
            })
        }
    }

    // ------------------------------------------------------------------ builders

    private fun radio(id: Int, label: String, detail: String, checked: Boolean) = MaterialRadioButton(this).apply {
        this.id = id
        text = android.text.SpannableStringBuilder(label).apply {
            append("\n")
            val start = length
            append(detail)
            setSpan(android.text.style.ForegroundColorSpan(ui.onSurfaceVariant), start, length, 0)
            setSpan(android.text.style.RelativeSizeSpan(0.85f), start, length, 0)
        }
        isChecked = checked
        setPadding(ui.dp(8), ui.dp(10), 0, ui.dp(10))
    }

    private fun switch(label: String, on: Boolean, change: (Boolean) -> Unit) = MaterialSwitch(this).apply {
        text = label; isChecked = on; textSize = 15f; minHeight = ui.dp(52)
        setOnCheckedChangeListener { _, checked -> change(checked) }
    }

    private fun example(lead: String, says: String) = ui.column().apply {
        setPadding(0, ui.dp(6), 0, ui.dp(6))
        addView(ui.hint(lead).apply { textSize = 13f })
        addView(ui.body(says).apply { setTextColor(ui.primary) })
    }

    private fun <T : View> T.withTopMargin(dp: Int = 12): T = apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = ui.dp(dp) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
