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
    private var dictField: android.widget.EditText? = null
    private var planBox: LinearLayout? = null
    private var signingIn = false
    private val downloading = mutableSetOf<ModelManager.Model>()

    // Live one-line summaries on the home rows.
    private val speechSum by lazy { TextView(this) }
    private val cleanupSum by lazy { TextView(this) }
    private val styleSum by lazy { TextView(this) }
    private val talkSum by lazy { TextView(this) }
    private val dictSum by lazy { TextView(this) }
    private val snipSum by lazy { TextView(this) }
    private val storageSum by lazy { TextView(this) }
    private val keySum by lazy { TextView(this) }
    private val bubbleSum by lazy { TextView(this) }
    private val bubbleBox by lazy { ui.column() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        models = ModelManager(this)
        if (savedInstanceState == null && !Prefs.setupDone(this) && !allSet()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        val speechModels = ModelManager.Model.entries.filter { it.kind == ModelManager.Kind.SPEECH }.sortedBy { it.approxMb }
        val cleanupModels = ModelManager.Model.entries.filter { it.kind == ModelManager.Kind.CLEANUP }.sortedBy { it.approxMb }
        fun bar() = LinearProgressIndicator(this).apply { max = 100; trackCornerRadius = ui.dp(4); visibility = View.GONE }
        speechBar = bar(); cleanupBar = bar()
        speechAction = ui.tonal("") { download(models.selected, speechAction, speechBar) }.withTopMargin()
        cleanupAction = ui.tonal("") { models.selectedCleanup?.let { download(it, cleanupAction, cleanupBar) } }.withTopMargin()

        // ---- sheet contents (built once; refresh() keeps them current)
        val speechSheet = ui.column().apply {
            addView(ui.hint("Turns your voice into words, right on this phone."))
            addView(RadioGroup(context).apply {
                speechModels.forEachIndexed { i, m ->
                    addView(option(1000 + i, m, m == models.recommended, m == models.selected))
                }
                setOnCheckedChangeListener { _, id ->
                    val m = speechModels[id - 1000]
                    // Switched models: free the old one now rather than at the next dictation.
                    if (m != models.selected) lifecycleScope.launch { dev.offlineflow.core.Brain.releaseAsr() }
                    models.selected = m; refresh()
                }
            })
            addView(speechBar); addView(speechAction)
        }
        val cleanupSheet = ui.column().apply {
            addView(ui.hint("Drops the ums, fixes punctuation and spells your words right. " +
                if (BuildConfig.CLOUD) "Runs on this phone whenever ChatGPT isn't in use." else "Runs on this phone."))
            addView(RadioGroup(context).apply {
                cleanupModels.forEachIndexed { i, m ->
                    addView(option(2000 + i, m, m == models.recommendedCleanup, m == models.selectedCleanup))
                }
                addView(radio(2999, "Off", "Just punctuation and your words.", models.selectedCleanup == null))
                setOnCheckedChangeListener { _, id ->
                    val m = if (id == 2999) null else cleanupModels[id - 2000]
                    if (m != models.selectedCleanup) lifecycleScope.launch { dev.offlineflow.core.Brain.releaseLlm() }
                    models.selectedCleanup = m; refresh()
                }
            })
            addView(cleanupBar); addView(cleanupAction)
        }
        stylesInfo = ui.hint("")
        stylesReset = ui.text("Reset all apps") {
            Prefs.resetStyleOverrides(this@SettingsActivity); refresh(); toast("Styles reset")
        }
        val styleSheet = ui.column().apply {
            addView(ui.hint("Hearsay picks a style for each app. Tap the chip on the keyboard to change it there."))
            addView(ui.space(8))
            addView(example("Casual", "Messages and chats. Relaxed, like you talk."))
            addView(example("Polished", "Email, docs and work apps. Clean and complete."))
            addView(example("Verbatim", "Code, links and search. Exactly what you said."))
            addView(ui.space(8)); addView(stylesInfo); addView(stylesReset)
        }
        val talkSheet = ui.column().apply {
            addView(ui.hint("Hold the mic while you talk, or tap once and Hearsay stops when you pause for:"))
            addView(ui.space(12))
            addView(MaterialButtonToggleGroup(context).apply {
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
                addOnButtonCheckedListener { _, id, checked -> if (checked) ids[id]?.let { Prefs.setPauseMs(context, it); refresh() } }
            })
            addView(ui.space(8))
            addView(switch("Vibrate when the mic starts and stops", Prefs.haptics(context)) { Prefs.setHaptics(context, it); refresh() })
        }
        val commandsSheet = ui.column().apply {
            addView(ui.hint("Edit with your voice instead of your thumbs."))
            addView(ui.space(4))
            addView(example("Select text, then say", "\u201cmake this shorter\u201d \u00b7 \u201cmore formal\u201d \u00b7 \u201cturn it into bullets\u201d", quote = true))
            addView(example("Right after dictating", "\u201cmake that friendlier\u201d \u00b7 \u201cscratch that\u201d", quote = true))
            addView(ui.space(6))
            addView(ui.hint("Changed your mind? The undo key brings back what you said."))
        }
        val (dictBox, dict) = ui.field(Dictionary.text(this), "One per line", multiline = 5)
        dictField = dict
        val dictSheet = ui.column().apply {
            addView(ui.hint("Names and jargon Hearsay should spell your way. If it keeps mishearing something, " +
                "teach it: hear say -> Hearsay"))
            addView(switch("Learn from my fixes", Prefs.learnWords(context)) { Prefs.setLearnWords(context, it) })
            addView(ui.hint("Fix a name Hearsay got wrong and it adds it here by itself. Everyday words are never learned."))
            addView(ui.space(8))
            addView(dictBox)
            addView(ui.filled("Save") {
                Dictionary.save(this@SettingsActivity, dict.text.toString())
                val d = Dictionary.load(this@SettingsActivity)
                toast("Saved ${d.terms.size} words, ${d.fixes.size} fixes"); refresh()
            }.withTopMargin())
        }
        val (snipBox, snips) = ui.field(Snippets.text(this), "One per line", multiline = 4)
        val snipSheet = ui.column().apply {
            addView(ui.hint("Say a short phrase, get the full text. For example: my email = you@example.com"))
            addView(snipBox)
            addView(ui.filled("Save") {
                Snippets.save(this@SettingsActivity, snips.text.toString())
                toast("Saved ${Snippets.load(this@SettingsActivity).entries.size} snippets"); refresh()
            }.withTopMargin())
        }
        val bubbleSheet = ui.column().apply {
            addView(ui.hint("A mic that floats above whatever keyboard you use. Tap a text box and it appears. " +
                "Hold to talk, tap for hands-free, drag to move it."))
            addView(ui.space(12)); addView(bubbleBox)
            addView(ui.space(8))
            addView(switch("Spell names on screen", Prefs.screenContext(context)) { Prefs.setScreenContext(context, it) })
            addView(ui.hint("When you stop talking, Hearsay looks at the names shown in the app (a chat, an email thread) " +
                "so it spells them the same way. It happens on this phone and nothing is saved."))
        }
        storageBox = ui.column()
        val storageSheet = ui.column().apply { addView(ui.hint("Models downloaded to this phone.")); addView(ui.space(4)); addView(storageBox) }

        val content = ui.column(ui.dp(16), 0).apply {
            setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(32))

            addView(ui.row().apply {
                addView(ImageView(context).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
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
                planBox = ui.column()
                addView(ui.card(
                    ui.row().apply {
                        addView(ui.icon(R.drawable.ic_sparkle, ui.primary, 22))
                        addView(ui.title("Boost with ChatGPT", 18f).apply { setPadding(ui.dp(10), 0, 0, 0) })
                    },
                    ui.space(6),
                    ui.hint("Sharper cleanup and edits with your ChatGPT plan. Your voice still stays on this phone."),
                    ui.space(14),
                    planBox!!,
                ))
            }

            addView(ui.overline("Dictation"))
            addView(ui.group(
                ui.listRow(R.drawable.ic_mic, "Floating mic", bubbleSum) { sheet("Floating mic", bubbleSheet) },
                ui.listRow(R.drawable.ic_wave, "Voice", speechSum) { sheet("Voice", speechSheet) },
                ui.listRow(R.drawable.ic_sparkle, "Cleanup", cleanupSum) { sheet("Cleanup", cleanupSheet) },
                ui.listRow(R.drawable.ic_lines, "Writing style", styleSum) { sheet("Writing style", styleSheet) },
                ui.listRow(R.drawable.ic_timer, "Hands-free", talkSum) { sheet("Hands-free", talkSheet) },
                ui.listRow(R.drawable.ic_chat, "Voice commands", TextView(context).apply { text = "Edit text by saying what to change" }) {
                    sheet("Voice commands", commandsSheet)
                },
            ))
            addView(ui.overline("Your words"))
            addView(ui.group(
                ui.listRow(R.drawable.ic_book, "Dictionary", dictSum) { sheet("Dictionary", dictSheet) },
                ui.listRow(R.drawable.ic_bolt, "Snippets", snipSum) { sheet("Snippets", snipSheet) },
            ))
            addView(ui.overline("More"))
            val more = mutableListOf<View>(ui.listRow(R.drawable.ic_storage, "Storage", storageSum) { sheet("Storage", storageSheet) })
            if (BuildConfig.CLOUD) more += ui.listRow(R.drawable.ic_key, "Your own API key", keySum) { sheet("Your own API key", advancedCloud()) }
            addView(ui.group(*more.toTypedArray()))

            addView(ui.space(24))
            addView(ui.row().apply {
                gravity = Gravity.CENTER
                addView(ui.icon(R.drawable.ic_lock, ui.onSurfaceVariant, 16))
                addView(ui.hint("  Audio stays on this phone unless you turn on cloud speech."))
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

    /** Opens [body] in a bottom sheet under [title]. Views are reused, so detach from the last sheet first. */
    private fun sheet(title: String, body: View) {
        (body.parent as? ViewGroup)?.removeView(body)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(ScrollView(this).apply {
            addView(ui.column(ui.dp(24), 0).apply {
                setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(32))
                addView(ui.title(title, 22f).apply { setPadding(0, 0, 0, ui.dp(8)) })
                addView(body)
            })
        })
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { (body.parent as? ViewGroup)?.removeView(body) }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        // Words learned while away show up in the dictionary box (unless it's being edited).
        dictField?.let { f -> if (!f.hasFocus()) Dictionary.text(this).let { if (f.text.toString() != it) f.setText(it) } }
        refresh()
    }

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
    private fun bubbleOk() = dev.offlineflow.bubble.BubbleService.enabled(this)
    private fun allSet() = micOk() && models.isReady(models.selected) && (enabledOk() || bubbleOk())

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
        refreshSummaries()
    }

    private fun refreshSummaries() {
        val sp = models.selected
        speechSum.text = "${sp.tier} \u00b7 " + if (models.isReady(sp)) "on this phone" else "tap to download"
        val c = models.selectedCleanup
        cleanupSum.text = when {
            BuildConfig.CLOUD && ChatGptPlan.account(this) != null && Prefs.chatGptCleanup(this) ->
                "ChatGPT" + (c?.let { " \u00b7 ${it.tier} on this phone when offline" } ?: "")
            c == null -> "Off \u00b7 punctuation only"
            models.isReady(c) -> "${c.tier} \u00b7 on this phone"
            else -> "${c.tier} \u00b7 tap to download"
        }
        val n = Prefs.styleOverrides(this)
        styleSum.text = if (n == 0) "Casual, Polished or Verbatim, picked per app" else "Custom in $n app${if (n == 1) "" else "s"}"
        val pause = Prefs.PAUSES.firstOrNull { it.first == Prefs.pauseMs(this) }?.second?.substringAfter("· ") ?: "1.5 s"
        talkSum.text = "Tap the mic once \u00b7 stops after a $pause pause"
        val d = Dictionary.load(this)
        dictSum.text = if (d.terms.isEmpty() && d.fixes.isEmpty()) "Teach Hearsay names and jargon"
            else listOfNotNull(d.terms.size.takeIf { it > 0 }?.let { "$it word${if (it == 1) "" else "s"}" },
                d.fixes.size.takeIf { it > 0 }?.let { "$it fix${if (it == 1) "" else "es"}" }).joinToString(" \u00b7 ")
        val sn = Snippets.load(this).entries.size
        snipSum.text = if (sn == 0) "Say a phrase, get your email, address\u2026" else "$sn snippet${if (sn == 1) "" else "s"}"
        val bytes = ModelManager.Model.entries.sumOf { models.bytesOnDisk(it) }
        storageSum.text = if (bytes == 0L) "Nothing downloaded yet" else "%.1f GB used by models".format(bytes / 1e9)
        bubbleSum.text = when {
            !bubbleOk() -> "Off \u00b7 use your own keyboard with Hearsay"
            !Prefs.bubbleOn(this) -> "Paused"
            else -> "On \u00b7 floats above any keyboard"
        }
        refreshBubble()
        keySum.text = if (Prefs.cloudSpeech(this) || Prefs.cloudCleanup(this)) "On" else "Off \u00b7 for OpenAI-compatible services"
    }

    private fun refreshStatus() {
        statusBox.removeAllViews()
        if (!allSet()) {
            val next = when {
                !micOk() -> "Next: allow the microphone."
                !models.isReady(models.selected) -> "Next: download the speech model."
                else -> "Next: turn on the floating mic."
            }
            statusBox.addView(ui.title("Finish setting up", 18f))
            statusBox.addView(ui.hint(next))
            statusBox.addView(ui.filled("Continue setup") { startActivity(Intent(this, SetupActivity::class.java)) }.withTopMargin(14))
            return
        }
        val cleanup = when {
            BuildConfig.CLOUD && ChatGptPlan.account(this) != null && Prefs.chatGptCleanup(this) -> "ChatGPT cleanup"
            models.selectedCleanup?.let { models.isReady(it) } == true -> "${models.selectedCleanup!!.tier} cleanup"
            else -> "basic cleanup"
        }
        statusBox.addView(ui.row().apply {
            addView(ui.icon(R.drawable.ic_check, ui.primary, 28))
            addView(ui.column().apply {
                setPadding(ui.dp(14), 0, 0, 0)
                addView(ui.title("Ready to dictate", 18f))
                addView(ui.hint("${models.selected.tier} voice \u00b7 $cleanup \u00b7 " +
                    if (bubbleOk()) "floating mic" else "Hearsay keyboard"))
            })
        })
        if (!bubbleOk() && !currentOk()) statusBox.addView(ui.tonal("Switch to Hearsay keyboard") {
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
        button.text = "Download ${m.tier} \u00b7 ${m.size}"
        button.isEnabled = true
        bar.setProgressCompat(0, false)
    }

    private fun download(m: ModelManager.Model, button: MaterialButton, bar: LinearProgressIndicator) {
        if (m in downloading || models.isReady(m)) return
        downloading += m
        button.text = "Downloading\u2026"; button.isEnabled = false
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
        box.addView(ui.hint(if (on) "Cleanup and commands use ChatGPT (${Prefs.chatGptModel(this)})" else "Paused \u00b7 cleanup runs on this phone"))
        box.addView(switch("Use ChatGPT for cleanup", on) { Prefs.setChatGptCleanup(this, it); refresh() })
        box.addView(ui.row().apply {
            addView(ui.text("Model") {
                val (modelBox, model) = ui.field(Prefs.chatGptModel(this@SettingsActivity), "Model")
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("ChatGPT model")
                    .setView(ui.column(ui.dp(24), ui.dp(8)).apply { addView(modelBox) })
                    .setPositiveButton("Save") { _, _ -> Prefs.setChatGptModel(this@SettingsActivity, model.text.toString().trim()); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            addView(ui.text("Usage") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ChatGptPlan.USAGE_URL))) })
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

    private fun advancedCloud(): View {
        val details = ui.column()
        val (urlBox, url) = ui.field(Prefs.cloudUrl(this), "Base URL (https)")
        val (keyBox, key) = ui.field(Prefs.cloudKey(this), "API key", secret = true)
        val (asrBox, asrModel) = ui.field(Prefs.cloudSpeechModel(this), "Speech model")
        val (llmBox, llmModel) = ui.field(Prefs.cloudCleanupModel(this), "Cleanup model")
        var speech = Prefs.cloudSpeech(this); var cleanup = Prefs.cloudCleanup(this)
        details.addView(ui.hint("For developers with an OpenAI-compatible service. When on, your audio or text goes there. " +
            "ChatGPT, when signed in, comes first for cleanup."))
        listOf(urlBox, keyBox, asrBox, llmBox).forEach { details.addView(it) }
        details.addView(switch("Send audio for transcription", speech) { speech = it })
        details.addView(switch("Use for cleanup and commands", cleanup) { cleanup = it })
        details.addView(ui.filled("Save") {
            val u = url.text.toString().trim()
            if ((speech || cleanup) && !u.startsWith("https://")) { toast("The address must start with https://"); return@filled }
            if ((speech || cleanup) && key.text.isNullOrBlank()) { toast("Add your API key first"); return@filled }
            Prefs.saveCloud(this, u, key.text.toString(), asrModel.text.toString(), llmModel.text.toString(), speech, cleanup)
            toast(if (speech || cleanup) "Own-key cloud on" else "Saved \u00b7 fully on-device")
            refresh()
        }.withTopMargin())
        return details
    }

    // ------------------------------------------------------------------ floating mic

    private fun refreshBubble() {
        val box = bubbleBox
        box.removeAllViews()
        if (!bubbleOk()) {
            box.addView(ui.filled("Turn on floating mic") { openAccessibility(this) })
            box.addView(ui.space(12))
            box.addView(ui.hint("In the list, tap Hearsay floating mic and switch it on. Android will warn you about " +
                "accessibility. Hearsay reads the text box you're dictating into (and names on screen, if you allow it), and nothing leaves your phone."))
            box.addView(ui.space(8))
            box.addView(ui.hint("Switch greyed out? Open App info, tap \u22ee at the top, then Allow restricted settings."))
            box.addView(ui.text("Open App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            return
        }
        box.addView(switch("Show the floating mic", Prefs.bubbleOn(this)) { Prefs.setBubbleOn(this, it); refresh() })
        box.addView(ui.row().apply {
            addView(ui.text("Reset position") { Prefs.resetBubblePos(this@SettingsActivity); toast("Back to its spot above the keyboard") })
            addView(ui.text("Accessibility settings") { openAccessibility(this@SettingsActivity) })
        })
        // Why it did or didn't show in the last app where a keyboard was up.
        Prefs.bubbleWhy(this).split('|', limit = 2).takeIf { it.size == 2 }?.let { (pkg, why) ->
            val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            box.addView(ui.space(8))
            box.addView(ui.hint(if (why.isEmpty()) "Last seen: showing in $app." else "Last seen in $app: hidden because $why."))
        }
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

    /** A title + line pair; [quote] colors the second line as something you'd say. */
    private fun example(lead: String, says: String, quote: Boolean = false) = ui.column().apply {
        setPadding(0, ui.dp(8), 0, ui.dp(8))
        if (quote) {
            addView(ui.hint(lead).apply { textSize = 13f })
            addView(ui.body(says).apply { setTextColor(ui.primary) })
        } else {
            addView(ui.body(lead, 16f).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(ui.hint(says))
        }
    }

    /** A model choice in plain words: tier (+ Recommended), what it's good at, then size and the model's real name. */
    private fun option(id: Int, m: ModelManager.Model, recommended: Boolean, checked: Boolean) = MaterialRadioButton(this).apply {
        this.id = id
        text = android.text.SpannableStringBuilder().apply {
            append(m.tier)
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
            if (recommended) {
                val s0 = length
                append("  Recommended")
                setSpan(android.text.style.ForegroundColorSpan(ui.primary), s0, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.8f), s0, length, 0)
            }
            append("\n")
            val b0 = length
            append(m.blurb)
            setSpan(android.text.style.ForegroundColorSpan(ui.onSurfaceVariant), b0, length, 0)
            setSpan(android.text.style.RelativeSizeSpan(0.9f), b0, length, 0)
            append("\n")
            val d0 = length
            append("${m.size} \u00b7 ${m.shortName}")
            setSpan(android.text.style.ForegroundColorSpan(ui.onSurfaceVariant), d0, length, 0)
            setSpan(android.text.style.RelativeSizeSpan(0.78f), d0, length, 0)
        }
        isChecked = checked
        setPadding(ui.dp(8), ui.dp(12), 0, ui.dp(12))
    }

    private fun <T : View> T.withTopMargin(dp: Int = 12): T = apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = ui.dp(dp) }
    }

    companion object {
        /** Opens Accessibility settings, scrolled to Hearsay where the system supports it. */
        fun openAccessibility(a: android.app.Activity) {
            val name = android.content.ComponentName(a, dev.offlineflow.bubble.BubbleService::class.java).flattenToString()
            a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", name)
                putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", name) })
            })
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
