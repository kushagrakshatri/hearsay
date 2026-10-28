package dev.offlineflow.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.offlineflow.Prefs
import dev.offlineflow.asr.Engines
import dev.offlineflow.asr.SpeechEngine
import dev.offlineflow.audio.Recorder
import dev.offlineflow.audio.SpeechGate
import dev.offlineflow.cleanup.Cleaner
import dev.offlineflow.cleanup.Commands
import dev.offlineflow.cleanup.Continuity
import dev.offlineflow.cloud.CloudClient
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
    private val asrDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val llmDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var cleaner: LlmCleaner? = null
    private var cleanerModel: ModelManager.Model? = null
    private var cleanerDictKey: String? = null
    private lateinit var models: ModelManager
    private var engine: SpeechEngine? = null
    private var engineModel: ModelManager.Model? = null
    private var recorder: Recorder? = null
    private var micDownAt = 0L
    private var handsFree = false

    private lateinit var status: TextView
    private lateinit var mic: Button
    private lateinit var styleChip: Button
    private var style = Style.POLISHED
    private var editorPkg: String? = null
    private var lastRaw: String? = null
    private var lastInserted: String? = null
    private var hasSelection = false
    private var idleJob: Job? = null
    private lateinit var wave: LevelView

    override fun onCreate() {
        super.onCreate()
        models = ModelManager(this)
    }

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        status = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 14f; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        mic = Button(this).apply {
            text = "Hold to talk"; textSize = 18f; isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = 48 * dp; setColor(Color.parseColor("#2E7D32")) }
            setTextColor(Color.WHITE)
            setOnTouchListener(::onMicTouch)
        }
        val raw = Button(this).apply { text = "Raw"; isAllCaps = false; setOnClickListener { swapToRaw() } }
        styleChip = Button(this).apply {
            isAllCaps = false; textSize = 13f
            setOnClickListener {
                style = style.next()
                editorPkg?.let { Style.save(this@OfflineFlowIME, it, style) }
                text = style.label
                status.text = "${style.label} in this app" + if (style == Style.VERBATIM) " · no AI rewrite" else ""
                warmStyle()
            }
        }
        val next = Button(this).apply {
            text = "⌨"; setOnClickListener {
                if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod()
            }
        }
        val back = Button(this).apply {
            text = "⌫"; setOnClickListener { currentInputConnection?.deleteSurroundingText(1, 0) }
        }
        wave = LevelView(this).apply { visibility = View.GONE }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(next); addView(mic, LinearLayout.LayoutParams(0, (72 * dp).toInt(), 1f)); addView(raw); addView(back)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(styleChip)
            })
            addView(wave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (28 * dp).toInt()))
            addView(row)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        idleJob?.cancel(); idleJob = null
        lastRaw = null; lastInserted = null
        hasSelection = info != null && info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        mic.text = idleLabel()
        editorPkg = info?.packageName
        style = Style.forEditor(this, info)
        styleChip.text = style.label
        status.text = when {
            !hasMicPermission() -> "Open the Hearsay app to grant microphone access"
            !speechAvailable() -> "Open the Hearsay app to download the speech model"
            CloudClient.speechOn(this) || CloudClient.cleanupOn(this) -> "Ready · cloud mode on"
            else -> "Ready · on-device · ${models.selected.shortName}"
        }
        warmUp()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        recorder?.stop(); recorder = null
        handsFree = false
        if (::mic.isInitialized) mic.text = idleLabel()
        if (::wave.isInitialized) { wave.visibility = View.GONE; wave.clear() }
        // Keyboard hidden: free the cleanup LLM after a minute and the speech model after five,
        // so a hidden keyboard doesn't hold gigabytes of RAM. Reopening cancels this.
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(LLM_IDLE_MS)
            releaseLlm()
            delay(ASR_IDLE_MS - LLM_IDLE_MS)
            releaseAsr()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (recorder != null) return
        val hidden = !isInputViewShown
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || (hidden && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)) {
            Log.i(TAG, "trim memory level=$level, releasing models")
            scope.launch { releaseLlm(); releaseAsr() }
        }
    }

    private suspend fun releaseLlm() = withContext(llmDispatcher) {
        cleaner?.close(); cleaner = null; cleanerModel = null; cleanerDictKey = null
    }

    private suspend fun releaseAsr() = withContext(asrDispatcher) {
        engine?.close(); engine = null; engineModel = null
    }

    /** Local model downloaded, or cloud speech configured. */
    private fun speechAvailable() = models.isReady(models.selected) || CloudClient.speechOn(this)

    /** Local cleanup model downloaded, or cloud cleanup configured. */
    private fun cleanupAvailable() =
        models.selectedCleanup?.let { models.isReady(it) } == true || CloudClient.cleanupOn(this)

    override fun onDestroy() {
        scope.cancel()
        engine?.close(); engine = null
        cleaner?.close(); cleaner = null
        super.onDestroy()
    }

    /** Returns the engine for the selected model, (re)loading it if the choice changed. ASR thread only. */
    private fun engineForSelected(): SpeechEngine {
        val want = models.selected
        engine?.let { if (engineModel == want) return it }
        engine?.close(); engine = null; engineModel = null
        return Engines.load(models, want).also { engine = it; engineModel = want }
    }

    /** Returns the selected cleanup LLM, or RulesCleaner when off / not downloaded. LLM thread only. */
    private fun cleanerForSelected(dictionary: Dictionary = Dictionary.load(this)): Cleaner {
        val want = models.selectedCleanup?.takeIf { models.isReady(it) }
        if (want == null) {
            cleaner?.close(); cleaner = null; cleanerModel = null
            return RulesCleaner
        }
        // The dictionary is part of the cached prompt, so a dictionary edit reloads the prefix.
        cleaner?.let { if (cleanerModel == want && cleanerDictKey == dictionary.key) return it }
        cleaner?.close(); cleaner = null; cleanerModel = null
        return LlmCleaner.load(want, models.file(want), dictionary).also {
            cleaner = it; cleanerModel = want; cleanerDictKey = dictionary.key
        }
    }

    private fun warmUp() {
        if (models.isReady(models.selected) && !CloudClient.speechOn(this)) scope.launch(asrDispatcher) {
            runCatching { engineForSelected() }.onFailure { Log.e(TAG, "speech model load failed", it) }
        }
        warmStyle()
    }

    /** Loads the cleanup model and evaluates the current style's prompt ahead of use. */
    private fun warmStyle() {
        val s = style
        if (CloudClient.cleanupOn(this)) return // cloud cleanup: keep the local LLM unloaded
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
                    status.text = "Listening… stops when you pause, or tap"
                    mic.text = "Tap to stop"
                } else finishDictation()
            }
            MotionEvent.ACTION_CANCEL -> if (!handsFree) finishDictation()
        }
        return true
    }

    private fun startDictation() {
        if (!hasMicPermission() || !speechAvailable()) return
        status.text = "Listening…"
        val gate = SpeechGate.create(assets)
        buzz()
        wave.clear(); wave.visibility = View.VISIBLE
        recorder = Recorder(gate, endSilenceMs = Prefs.pauseMs(this), onSpeechEnd = {
            // Mic thread → main. Only hands-free ends on a pause; holding keeps listening.
            mic.post { if (handsFree) finishDictation() }
        }, onLevel = { level -> wave.post { wave.push(level) } }).also { it.start() }
    }

    private fun finishDictation() {
        val rec = recorder ?: return
        recorder = null
        handsFree = false
        mic.text = idleLabel()
        buzz()
        wave.visibility = View.GONE
        val audio = rec.stop()
        // Captured now: the selection decides whether this is a command or a replacement.
        val selected = currentInputConnection?.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() }
        if (!rec.heardSpeech) { status.text = "Didn't hear any speech"; return }
        if (audio.size < Recorder.SAMPLE_RATE / 4) { status.text = "Too short"; return }
        val cloud = CloudClient.from(this)?.takeIf { CloudClient.speechOn(this) }
        status.text = when {
            cloud != null -> "Transcribing (cloud)…"
            engine == null || engineModel != models.selected -> "Loading model, then transcribing…"
            else -> "Transcribing…"
        }
        val t0 = SystemClock.elapsedRealtime()
        val cloudAsrModel = Prefs.cloudSpeechModel(this)
        scope.launch {
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
                status.text = "Transcription failed: ${t.message ?: t.javaClass.simpleName}"
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
                status.text = "Snippet inserted · ${asrMs} ms"
                return@launch
            }
            val s = style
            val useLlm = s != Style.VERBATIM && cleanupAvailable() && raw.isNotBlank()
            if (useLlm) status.text = "Cleaning up…"
            val t1 = SystemClock.elapsedRealtime()
            val dictionary = Dictionary.load(this@OfflineFlowIME)
            val heard = dictionary.apply(raw)
            val cloudClean = CloudClient.from(this@OfflineFlowIME)?.takeIf { CloudClient.cleanupOn(this@OfflineFlowIME) }
            val cloudModel = Prefs.cloudCleanupModel(this@OfflineFlowIME)
            val cleaned = if (s == Style.VERBATIM) RulesCleaner.verbatim(heard) else try {
                if (cloudClean != null) withContext(Dispatchers.IO) {
                    dictionary.apply(LlmCleaner.cleanWith(heard, s) { input, words ->
                        cloudClean.chat(cloudModel, LlmCleaner.cleanupMessages(s, dictionary.spellings, input), words * 2 + 32)
                    })
                } else withContext(llmDispatcher) {
                    when (val c = cleanerForSelected(dictionary)) {
                        is LlmCleaner -> dictionary.apply(c.clean(heard, s))
                        else -> dictionary.apply(rulesFor(heard, s))
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "cleanup failed, using rules", t)
                dictionary.apply(rulesFor(heard, s))
            }
            val cleanMs = SystemClock.elapsedRealtime() - t1
            val ms = SystemClock.elapsedRealtime() - t0
            if (cleaned.isNotEmpty()) {
                val before = currentInputConnection?.getTextBeforeCursor(2, 0)
                val text = withLeadingSpace(if (s == Style.VERBATIM) cleaned else Continuity.apply(cleaned, before))
                currentInputConnection?.commitText(text, 1)
                lastRaw = withLeadingSpace(raw.trim()); lastInserted = text
            }
            status.text = if (cleaned.isEmpty()) "Heard nothing · ${ms} ms"
                else if (useLlm) "Done in ${ms} ms · speech ${asrMs} + cleanup ${cleanMs} · ${audio.size / Recorder.SAMPLE_RATE}s audio"
                else "Done in ${ms} ms · ${audio.size / Recorder.SAMPLE_RATE}s audio"
            Log.i(TAG, "latency=${ms}ms asr=${asrMs}ms cleanup=${cleanMs}ms audio=${audio.size}samples")
        }
    }

    /** Applies a spoken edit [utterance] to [target] (the selection, or the last insert) with the local LLM. */
    private suspend fun runCommand(target: String, utterance: String, onSelection: Boolean, t0: Long) {
        val instruction = Commands.instruction(utterance)
        if (target.length > MAX_EDIT_CHARS) { status.text = "Too much text for a command (about 400 words max)"; return }
        if (!cleanupAvailable()) {
            status.text = "Commands need a cleanup model · open Hearsay settings"; return
        }
        status.text = "Editing: $instruction…"
        val cloudClean = CloudClient.from(this)?.takeIf { CloudClient.cleanupOn(this) }
        val cloudModel = Prefs.cloudCleanupModel(this)
        val edited = try {
            if (cloudClean != null) withContext(Dispatchers.IO) {
                LlmCleaner.editWith(target.trim()) { max -> cloudClean.chat(cloudModel, LlmCleaner.editMessages(target.trim(), instruction), max) }
            } else withContext(llmDispatcher) { (cleanerForSelected() as? LlmCleaner)?.edit(target.trim(), instruction) }
        } catch (t: Throwable) {
            Log.e(TAG, "command failed", t); null
        }
        if (edited == null) { status.text = "Couldn't apply \"$instruction\""; return }
        val ic = currentInputConnection ?: return
        if (onSelection) {
            ic.commitText(edited, 1) // replaces the selection
            lastRaw = target; lastInserted = edited
        } else {
            val ins = lastInserted ?: return
            if (ic.getTextBeforeCursor(ins.length, 0)?.toString() != ins) { status.text = "Text changed, command skipped"; return }
            val text = (if (ins.startsWith(" ")) " " else "") + edited
            ic.deleteSurroundingText(ins.length, 0)
            ic.commitText(text, 1)
            lastRaw = ins; lastInserted = text
        }
        status.text = "$instruction · ${SystemClock.elapsedRealtime() - t0} ms · Raw undoes it"
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
            status.text = "Removed the last dictation"
        } else status.text = "Nothing to scratch"
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val sel = newSelStart != newSelEnd
        if (sel == hasSelection) return
        hasSelection = sel
        if (::mic.isInitialized && recorder == null) mic.text = idleLabel()
        if (sel && !CloudClient.cleanupOn(this)) scope.launch(llmDispatcher) {
            runCatching { (cleanerForSelected() as? LlmCleaner)?.warmEdit() }.onFailure { Log.e(TAG, "edit warm-up failed", it) }
        }
    }

    private fun idleLabel() = if (hasSelection) "Talk to edit selection" else "Hold to talk"

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
        private const val LLM_IDLE_MS = 60_000L
        private const val ASR_IDLE_MS = 300_000L
    }
}
