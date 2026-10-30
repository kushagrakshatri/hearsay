package dev.offlineflow.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M
import dev.offlineflow.R
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import dev.offlineflow.Prefs
import dev.offlineflow.core.Brain
import dev.offlineflow.asr.Engines
import dev.offlineflow.asr.SpeechEngine
import dev.offlineflow.audio.Recorder
import dev.offlineflow.audio.SpeechGate
import dev.offlineflow.cleanup.Cleaner
import dev.offlineflow.cleanup.Commands
import dev.offlineflow.cleanup.Continuity
import dev.offlineflow.cleanup.CorrectionWatch
import dev.offlineflow.cleanup.ScreenNames
import dev.offlineflow.cleanup.Words
import dev.offlineflow.core.Learning
import android.view.inputmethod.ExtractedTextRequest
import dev.offlineflow.cloud.CloudClient
import dev.offlineflow.cloud.CloudText
import dev.offlineflow.cleanup.Dictionary
import dev.offlineflow.cleanup.LlmCleaner
import dev.offlineflow.cleanup.RulesCleaner
import dev.offlineflow.cleanup.Snippets
import dev.offlineflow.cleanup.Style
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.*

/**
 * Keyboard: hold the mic and talk, or tap it for hands-free (stops on its own when you pause,
 * or tap again). Raw on-device transcript, lightly
 * cleaned up by a local LLM (or RulesCleaner when cleanup is off), is committed at the cursor. "Raw" swaps the last insert
 * back to the exact transcript.
 */
class OfflineFlowIME : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Models live in Brain, shared with the floating mic.
    private val asrDispatcher = Brain.asr
    private val llmDispatcher = Brain.llm
    private lateinit var models: ModelManager
    private var recorder: Recorder? = null
    private var micDownAt = 0L
    private var handsFree = false

    private lateinit var status: TextView
    private lateinit var mic: MicButton
    private lateinit var micHint: TextView
    private lateinit var undoKey: ImageView
    private lateinit var styleChip: TextView
    private var style = Style.POLISHED
    private var editorPkg: String? = null
    private var lastRaw: String? = null
    /** Why the last cloud call fell back to on-device, shown once in the status line. */
    private var cloudNote: String? = null
    private var lastInserted: String? = null
    private var hasSelection = false
    private val watch = CorrectionWatch()
    private var fieldKey: String? = null
    private lateinit var wave: LevelView

    override fun onCreate() {
        super.onCreate()
        models = ModelManager(this)
    }

    override fun onCreateInputView(): View {
        // Material You colors from the wallpaper on Android 12+, brand colors otherwise; follows dark mode.
        val t = DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(this, R.style.Theme_Hearsay))
        fun c(attr: Int) = MaterialColors.getColor(t, attr, Color.GRAY)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val onSurface = c(M.attr.colorOnSurface)
        val keyBg = c(M.attr.colorSurfaceContainerHighest)

        status = TextView(t).apply {
            textSize = 14f; setTextColor(c(M.attr.colorOnSurfaceVariant)); maxLines = 2
            ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            compoundDrawablePadding = px(6)
            setPadding(px(16), 0, px(16), 0)
        }
        statusIconTint = c(M.attr.colorOnSurfaceVariant)
        styleChip = TextView(t).apply {
            textSize = 13f; setTextColor(c(M.attr.colorOnSecondaryContainer)); gravity = Gravity.CENTER
            setPadding(px(14), px(6), px(14), px(6))
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(c(M.attr.colorSecondaryContainer)) }
            contentDescription = "Writing style. Tap to change for this app"
            setOnClickListener {
                style = style.next()
                editorPkg?.let { Style.save(this@OfflineFlowIME, it, style) }
                text = style.label
                say(when (style) {
                    Style.CASUAL -> "Casual here \u00b7 relaxed, like you talk"
                    Style.POLISHED -> "Polished here \u00b7 clean and complete"
                    Style.VERBATIM -> "Verbatim here \u00b7 exactly what you say"
                })
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                warmStyle()
            }
        }
        mic = MicButton(t).apply {
            idleColor = c(M.attr.colorPrimary); iconColor = c(M.attr.colorOnPrimary)
            activeColor = c(M.attr.colorError); busyColor = c(M.attr.colorPrimary)
            setOnTouchListener(::onMicTouch)
        }
        micHint = TextView(t).apply {
            textSize = 12f; setTextColor(c(M.attr.colorOnSurfaceVariant)); gravity = Gravity.CENTER
        }
        fun key(icon: Int, desc: String) = ImageView(t).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(onSurface)
            scaleType = ImageView.ScaleType.CENTER; contentDescription = desc
            background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 40)),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(keyBg) }, null)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(px(52), px(52)).apply { marginStart = px(4); marginEnd = px(4) }
        }
        val switchKey = key(R.drawable.ic_keyboard, "Switch keyboard").apply {
            setOnClickListener { tap(it); if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod() }
            setOnLongClickListener {
                getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker(); true
            }
        }
        undoKey = key(R.drawable.ic_undo, "Undo cleanup").apply { setOnClickListener { tap(it); swapToRaw() } }
        val backKey = key(R.drawable.ic_backspace, "Delete").apply { setOnTouchListener(Repeater { deleteOne() }) }
        val enterKey = key(R.drawable.ic_return, "Enter").apply { setOnClickListener { tap(it); enter() } }
        updateUndo()

        wave = LevelView(t).apply { visibility = View.INVISIBLE; color = c(M.attr.colorPrimary) }
        val gear = ImageView(t).apply {
            setImageResource(R.drawable.ic_settings); imageTintList = ColorStateList.valueOf(c(M.attr.colorOnSurfaceVariant))
            scaleType = ImageView.ScaleType.CENTER; contentDescription = "Hearsay settings"
            background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 40)), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            setOnClickListener {
                tap(it)
                startActivity(android.content.Intent(this@OfflineFlowIME, dev.offlineflow.SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val side = { g: Int -> LinearLayout(t).apply { orientation = LinearLayout.HORIZONTAL; gravity = g or Gravity.CENTER_VERTICAL } }
        val left = side(Gravity.START).apply { addView(switchKey); addView(undoKey) }
        val right = side(Gravity.END).apply { addView(backKey); addView(enterKey) }
        val main = LinearLayout(t).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(mic, LinearLayout.LayoutParams(px(112), px(112)))
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(t).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(M.attr.colorSurfaceContainer))
            setPadding(px(12), px(10), px(12), px(12))
            // Top: style on the left, settings on the right.
            addView(LinearLayout(t).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(styleChip)
                addView(View(t), LinearLayout.LayoutParams(0, 1, 1f))
                addView(gear, LinearLayout.LayoutParams(px(40), px(40)))
            })
            // Stage: what's happening right now. Words when idle or done, the live waveform while listening.
            addView(android.widget.FrameLayout(t).apply {
                addView(status, android.widget.FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                addView(wave, android.widget.FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(44), Gravity.CENTER).apply {
                    marginStart = px(24); marginEnd = px(24)
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(60)))
            addView(main)
            addView(micHint)
            setMic(MicButton.State.IDLE)
            // Android 15 draws the keyboard edge to edge: keep the hint clear of the gesture bar
            // and the system's back / switch-keyboard buttons.
            val base = paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.mandatorySystemGestures())
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + nav.bottom)
                insets
            }
        }
    }

    private var statusIconTint = Color.GRAY

    /** Puts [text] on the stage, with an optional small icon before it. */
    private fun say(text: String, icon: Int? = null) {
        if (!::status.isInitialized) return
        status.text = text
        val d = icon?.let { resources.getDrawable(it, null)?.mutate() }?.apply {
            val sz = (16 * resources.displayMetrics.density).toInt()
            setBounds(0, 0, sz, sz); setTint(statusIconTint)
        }
        status.setCompoundDrawablesRelative(d, null, null, null)
    }

    private fun secs(ms: Long) = if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000f)

    /** Sets the mic's look and the hint under it. */
    private fun setMic(state: MicButton.State) {
        if (!::mic.isInitialized) return
        mic.state = state
        micHint.text = when (state) {
            MicButton.State.IDLE -> if (hasSelection) "Talk to edit the selection" else "Hold to talk · tap for hands-free"
            MicButton.State.LISTENING -> "Listening… let go when done"
            MicButton.State.HANDS_FREE -> "Listening… pause or tap to stop"
            MicButton.State.BUSY -> ""
        }
    }

    private fun updateUndo() {
        if (!::undoKey.isInitialized) return
        val on = lastInserted != null && lastRaw != null && lastRaw != lastInserted
        undoKey.alpha = if (on) 1f else 0.35f; undoKey.isEnabled = on
    }

    private fun tap(v: View) { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }

    private fun enter() {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = ((info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else sendKeyChar('\n')
    }

    /** Fires once on press, then repeats while held (backspace). */
    private inner class Repeater(private val action: () -> Unit) : View.OnTouchListener {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var view: View? = null
        private val tick = object : Runnable {
            override fun run() { action(); handler.postDelayed(this, 50) }
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { view = v; v.isPressed = true; tap(v); action(); handler.postDelayed(tick, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(tick) }
            }
            return true
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Back in a field we dictated into (e.g. after fixing a word with another keyboard): learn from it.
        val key = info?.let { "${it.packageName}:${it.fieldId}" }
        learnFrom(key)
        fieldKey = key
        lastRaw = null; lastInserted = null
        hasSelection = info != null && info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        setMic(MicButton.State.IDLE); updateUndo()
        editorPkg = info?.packageName
        style = Style.forEditor(this, info)
        styleChip.text = style.label
        when {
            !hasMicPermission() -> say("Open Hearsay to allow the microphone")
            !speechAvailable() -> say("Open Hearsay to download a voice model")
            CloudText.usingPlan(this) -> say("Voice on this phone \u00b7 cleanup by ChatGPT", R.drawable.ic_sparkle)
            CloudClient.speechOn(this) -> say("Cloud speech is on")
            CloudText.on(this) -> say("Voice on this phone \u00b7 cloud cleanup")
            else -> say("Private \u00b7 everything stays on this phone", R.drawable.ic_lock)
        }
        warmUp()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        recorder?.stop(); recorder = null
        handsFree = false
        setMic(MicButton.State.IDLE)
        if (::wave.isInitialized) { wave.visibility = View.INVISIBLE; wave.clear(); status.visibility = View.VISIBLE }
        // Models stay loaded while the keyboard is hidden: their weights are memory-mapped file
        // pages the system can drop on its own, and reloading made the next dictation slow.
        // Real memory pressure frees them in onTrimMemory.
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (recorder != null) return
        // Only real pressure: UI_HIDDEN and friends are also >= RUNNING_CRITICAL numerically.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            Log.i(TAG, "trim memory level=$level, releasing models")
            scope.launch { releaseLlm(); releaseAsr() }
        }
    }

    private suspend fun releaseLlm() = Brain.releaseLlm()

    private suspend fun releaseAsr() = Brain.releaseAsr()

    /** Local model downloaded, or cloud speech configured. */
    private fun speechAvailable() = models.isReady(models.selected) || CloudClient.speechOn(this)

    /** Local cleanup model downloaded, or cloud cleanup configured. */
    private fun cleanupAvailable() =
        models.selectedCleanup?.let { models.isReady(it) } == true || CloudText.on(this)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun engineForSelected(): SpeechEngine = Brain.engine(models)

    private fun cleanerForSelected(dictionary: Dictionary = Dictionary.load(this)): Cleaner = Brain.cleaner(models, dictionary)

    private fun warmUp() {
        if (models.isReady(models.selected) && !CloudClient.speechOn(this)) scope.launch(asrDispatcher) {
            runCatching { engineForSelected() }.onFailure { Log.e(TAG, "speech model load failed", it) }
        }
        warmStyle()
    }

    /** Loads the cleanup model and evaluates the current style's prompt ahead of use. */
    private fun warmStyle() {
        val s = style
        if (CloudText.on(this)) return // cloud cleanup: keep the local LLM unloaded
        scope.launch(llmDispatcher) {
            runCatching { (cleanerForSelected() as? LlmCleaner)?.warm(s) }
                .onFailure { Log.e(TAG, "cleanup model load failed", it) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onMicTouch(v: View, e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (handsFree) { finishDictation(); return true } // second tap ends hands-free
                micDownAt = SystemClock.elapsedRealtime()
                startDictation()
            }
            MotionEvent.ACTION_UP -> {
                if (recorder == null || handsFree) return true
                if (SystemClock.elapsedRealtime() - micDownAt < TAP_MS) {
                    handsFree = true
                    setMic(MicButton.State.HANDS_FREE)
                } else finishDictation()
            }
            MotionEvent.ACTION_CANCEL -> if (!handsFree) finishDictation()
        }
        return true
    }

    private fun startDictation() {
        if (!hasMicPermission() || !speechAvailable()) return
        // Models dropped under memory pressure: start loading now, while the user talks.
        if (!Brain.engineLoaded(models.selected) || !Brain.cleanerLoaded(models)) warmUp()
        status.visibility = View.INVISIBLE
        setMic(MicButton.State.LISTENING)
        val gate = SpeechGate.create(assets)
        buzz()
        wave.clear(); wave.visibility = View.VISIBLE
        recorder = Recorder(gate, endSilenceMs = Prefs.pauseMs(this), onSpeechEnd = {
            // Mic thread → main. Only hands-free ends on a pause; holding keeps listening.
            mic.post { if (handsFree) finishDictation() }
        }, onLevel = { level -> wave.post { wave.push(level); mic.push(level) } }).also { it.start() }
    }

    private fun finishDictation() {
        val rec = recorder ?: return
        recorder = null
        handsFree = false
        setMic(MicButton.State.IDLE)
        buzz()
        wave.visibility = View.INVISIBLE
        status.visibility = View.VISIBLE
        val audio = rec.stop()
        // Captured now: the selection decides whether this is a command or a replacement.
        val selected = currentInputConnection?.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() }
        if (!rec.heardSpeech) { say("Didn't catch that. Try again a little closer."); return }
        if (audio.size < Recorder.SAMPLE_RATE / 4) { say("Too quick. Hold the mic while you talk."); return }
        val cloud = CloudClient.from(this)?.takeIf { CloudClient.speechOn(this) }
        say(when {
            !Brain.engineLoaded(models.selected) -> "Warming up\u2026"
            else -> "Writing it down\u2026"
        })
        val t0 = SystemClock.elapsedRealtime()
        val cloudAsrModel = Prefs.cloudSpeechModel(this)
        setMic(MicButton.State.BUSY)
        scope.launch {
          try {
            val raw = try {
                if (cloud != null) {
                    try {
                        withContext(Dispatchers.IO) { cloud.transcribe(audio, cloudAsrModel) }
                    } catch (t: Throwable) {
                        // Cloud failed: fall back to the on-device model when there is one.
                        if (!models.isReady(models.selected)) throw t
                        Log.w(TAG, "cloud transcription failed, using on-device", t)
                        withContext(asrDispatcher) { engineForSelected().transcribe(audio) }
                    }
                } else withContext(asrDispatcher) {
                    engineForSelected().transcribe(audio)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "transcription failed", t)
                say("Couldn't write that down. ${t.message ?: t.javaClass.simpleName}")
                return@launch
            }
            val asrMs = SystemClock.elapsedRealtime() - t0
            // Command mode: "scratch that", or an edit instruction for the selection / last insert.
            if (selected == null && Commands.isScratch(raw)) { scratchLast(); return@launch }
            val target = when {
                selected != null && Commands.forSelection(raw) -> selected
                selected == null && Commands.forLastInsert(raw) && lastInsertAtCursor() -> lastInserted
                else -> null
            }
            if (target != null) { runCommand(target, raw, onSelection = selected != null, t0); return@launch }
            // A snippet trigger on its own inserts the saved text exactly, skipping cleanup.
            Snippets.load(this@OfflineFlowIME).match(raw)?.let { snippet ->
                val text = withLeadingSpace(snippet)
                currentInputConnection?.commitText(text, 1)
                lastRaw = withLeadingSpace(raw.trim()); lastInserted = text
                say("Snippet added", R.drawable.ic_bolt)
                return@launch
            }
            val s = style
            val useLlm = s != Style.VERBATIM && cleanupAvailable() && raw.isNotBlank()
            if (useLlm) say("Polishing\u2026", R.drawable.ic_sparkle)
            val t1 = SystemClock.elapsedRealtime()
            val saved = Dictionary.load(this@OfflineFlowIME)
            val names = namesNearCursor()
            val screen = Dictionary.exact(names)
            fun fixWords(t: String) = screen.apply(saved.apply(t))
            val heard = fixWords(raw)
            val ctx = this@OfflineFlowIME
            suspend fun local() = withContext(llmDispatcher) {
                when (val c = cleanerForSelected(saved)) {
                    is LlmCleaner -> fixWords(c.clean(heard, s, names))
                    else -> fixWords(rulesFor(heard, s))
                }
            }
            val cleaned = if (s == Style.VERBATIM) RulesCleaner.verbatim(heard) else try {
                if (CloudText.on(ctx)) try {
                    withContext(Dispatchers.IO) {
                        fixWords(LlmCleaner.cleanWithOrThrow(heard, s) { input, words ->
                            CloudText.chat(ctx, LlmCleaner.cleanupMessages(s, saved.spellings, input, names), words * 2 + 32)
                        })
                    }
                } catch (t: Throwable) {
                    // Offline, plan limit, signed out: fall back to the on-device model (or rules).
                    Log.w(TAG, "cloud cleanup failed, using on-device", t)
                    cloudNote = t.message
                    local()
                } else local()
            } catch (t: Throwable) {
                Log.e(TAG, "cleanup failed, using rules", t)
                fixWords(rulesFor(heard, s))
            }
            val cleanMs = SystemClock.elapsedRealtime() - t1
            val ms = SystemClock.elapsedRealtime() - t0
            if (cleaned.isNotEmpty()) {
                val before = currentInputConnection?.getTextBeforeCursor(2, 0)
                val text = withLeadingSpace(if (s == Style.VERBATIM) cleaned else Continuity.apply(cleaned, before))
                currentInputConnection?.commitText(text, 1)
                lastRaw = withLeadingSpace(raw.trim()); lastInserted = text
                startWatch(text)
            }
            if (cleaned.isEmpty()) say("Didn't catch any words")
            else if (cloudNote != null) say("Done \u00b7 ${secs(ms)} \u00b7 ChatGPT unavailable, cleaned on this phone", R.drawable.ic_check)
            else say("Done \u00b7 ${secs(ms)}" + if (useLlm || s == Style.VERBATIM) "" else " \u00b7 basic cleanup", R.drawable.ic_check)
            cloudNote = null
            Log.i(TAG, "latency=${ms}ms asr=${asrMs}ms cleanup=${cleanMs}ms audio=${audio.size}samples")
          } finally {
            if (recorder == null) setMic(MicButton.State.IDLE)
            updateUndo()
          }
        }
    }

    /** Applies a spoken edit [utterance] to [target] (the selection, or the last insert) with the local LLM. */
    private suspend fun runCommand(target: String, utterance: String, onSelection: Boolean, t0: Long) {
        val instruction = Commands.instruction(utterance)
        if (target.length > MAX_EDIT_CHARS) { say("That's a lot of text. Edit up to about 400 words at a time."); return }
        if (!cleanupAvailable()) {
            say("Voice edits need cleanup on. Turn it on in Hearsay."); return
        }
        say("${instruction.replaceFirstChar { it.uppercase() }}\u2026", R.drawable.ic_sparkle)
        suspend fun local() = withContext(llmDispatcher) { (cleanerForSelected() as? LlmCleaner)?.edit(target.trim(), instruction) }
        val edited = try {
            if (CloudText.on(this)) try {
                withContext(Dispatchers.IO) {
                    LlmCleaner.editWith(target.trim()) { max -> CloudText.chat(this@OfflineFlowIME, LlmCleaner.editMessages(target.trim(), instruction), max) }
                        ?: error("cloud edit returned nothing usable")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cloud command failed, using on-device", t)
                local()
            } else local()
        } catch (t: Throwable) {
            Log.e(TAG, "command failed", t); null
        }
        if (edited == null) { say("Couldn't do \u201c$instruction\u201d. Try saying it another way."); return }
        val ic = currentInputConnection ?: return
        if (onSelection) {
            ic.commitText(edited, 1) // replaces the selection
            lastRaw = target; lastInserted = edited
        } else {
            val ins = lastInserted ?: return
            if (ic.getTextBeforeCursor(ins.length, 0)?.toString() != ins) { say("The text changed, so I left it alone"); return }
            val text = (if (ins.startsWith(" ")) " " else "") + edited
            ic.deleteSurroundingText(ins.length, 0)
            ic.commitText(text, 1)
            lastRaw = ins; lastInserted = text
        }
        say("Done \u00b7 undo brings back the original", R.drawable.ic_check)
    }

    /** The field's whole text (as far as the app shares it) and where the cursor is. */
    private fun fieldText(): Pair<String, Int>? {
        val et = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val t = et.text?.toString() ?: return null
        return t to et.selectionEnd
    }

    /** Remembers what was just inserted, so a fix the user makes to it later can be learned. */
    private fun startWatch(piece: String) {
        val key = fieldKey ?: return
        val (text, cursor) = fieldText() ?: return
        watch.begin(key, text, cursor - piece.length, piece, SystemClock.elapsedRealtime())
    }

    private fun learnFrom(key: String?) {
        val w = watch.watching ?: return
        if (key == null || key != w) { watch.clear(); return }
        val now = fieldText()?.first ?: return
        val change = watch.edited(key, now, SystemClock.elapsedRealtime()) ?: return
        watch.clear()
        scope.launch {
            val added = withContext(Dispatchers.IO) { runCatching { Learning.learn(this@OfflineFlowIME, change.first, change.second) }.getOrDefault(emptyList()) }
            if (added.isNotEmpty()) say(Learning.message(added), R.drawable.ic_book)
        }
    }

    /** Names in the text already in the field (a reply thread, an email you're writing), for spelling. */
    private fun namesNearCursor(): List<String> {
        val before = currentInputConnection?.getTextBeforeCursor(1500, 0)?.toString().orEmpty()
        if (before.isBlank()) return emptyList()
        val common = Words.common(this)
        return ScreenNames.from(listOf(before), { w -> w.lowercase().replace('\u2019', '\'') in common }, limit = 15)
    }

    private fun lastInsertAtCursor(): Boolean {
        val ins = lastInserted ?: return false
        return currentInputConnection?.getTextBeforeCursor(ins.length, 0)?.toString() == ins
    }

    private fun scratchLast() {
        val ins = lastInserted
        if (ins != null && lastInsertAtCursor()) {
            currentInputConnection?.deleteSurroundingText(ins.length, 0)
            lastInserted = null; lastRaw = null
            say("Removed what you just said")
            updateUndo()
        } else say("Nothing to remove")
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val sel = newSelStart != newSelEnd
        if (sel == hasSelection) return
        hasSelection = sel
        if (recorder == null && ::mic.isInitialized && mic.state == MicButton.State.IDLE) setMic(MicButton.State.IDLE)
        if (sel && !CloudText.on(this)) scope.launch(llmDispatcher) {
            runCatching { (cleanerForSelected() as? LlmCleaner)?.warmEdit() }.onFailure { Log.e(TAG, "edit warm-up failed", it) }
        }
    }

    private fun rulesFor(text: String, s: Style): String {
        val t = RulesCleaner.clean(text)
        return if (s == Style.CASUAL && t.endsWith(".") && t.count { it in ".!?" } == 1) t.dropLast(1) else t
    }

    private fun swapToRaw() {
        val ic = currentInputConnection ?: return
        val ins = lastInserted ?: return
        val raw = lastRaw ?: return
        if (ic.getTextBeforeCursor(ins.length, 0)?.toString() == ins) {
            ic.deleteSurroundingText(ins.length, 0)
            ic.commitText(raw, 1)
            lastInserted = raw
        }
        updateUndo()
    }

    private fun withLeadingSpace(s: String): String {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)
        return if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " $s" else s
    }

    private fun buzz() {
        if (Prefs.haptics(this)) mic.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "OfflineFlowIME"
        private const val TAP_MS = 350L
        private const val MAX_EDIT_CHARS = 2400
    }
}
