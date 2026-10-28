package dev.offlineflow.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.offlineflow.asr.Engines
import dev.offlineflow.asr.SpeechEngine
import dev.offlineflow.audio.Recorder
import dev.offlineflow.audio.SpeechGate
import dev.offlineflow.cleanup.Cleaner
import dev.offlineflow.cleanup.LlmCleaner
import dev.offlineflow.cleanup.RulesCleaner
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
    private lateinit var models: ModelManager
    private var engine: SpeechEngine? = null
    private var engineModel: ModelManager.Model? = null
    private var recorder: Recorder? = null
    private var micDownAt = 0L
    private var handsFree = false

    private lateinit var status: TextView
    private lateinit var mic: Button
    private var lastRaw: String? = null
    private var lastInserted: String? = null

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
        val next = Button(this).apply {
            text = "⌨"; setOnClickListener {
                if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod()
            }
        }
        val back = Button(this).apply {
            text = "⌫"; setOnClickListener { currentInputConnection?.deleteSurroundingText(1, 0) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(next); addView(mic, LinearLayout.LayoutParams(0, (72 * dp).toInt(), 1f)); addView(raw); addView(back)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt())
            addView(status); addView(row)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastRaw = null; lastInserted = null
        status.text = when {
            !hasMicPermission() -> "Open the Hearsay app to grant microphone access"
            !models.isReady(models.selected) -> "Open the Hearsay app to download the speech model"
            else -> "Ready · on-device · ${models.selected.shortName}"
        }
        warmUp()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        recorder?.stop(); recorder = null
        handsFree = false
        if (::mic.isInitialized) mic.text = "Hold to talk"
    }

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
    private fun cleanerForSelected(): Cleaner {
        val want = models.selectedCleanup?.takeIf { models.isReady(it) }
        if (want == null) {
            cleaner?.close(); cleaner = null; cleanerModel = null
            return RulesCleaner
        }
        cleaner?.let { if (cleanerModel == want) return it }
        cleaner?.close(); cleaner = null; cleanerModel = null
        return LlmCleaner.load(want, models.file(want)).also { cleaner = it; cleanerModel = want }
    }

    private fun warmUp() {
        if (models.isReady(models.selected)) scope.launch(asrDispatcher) {
            runCatching { engineForSelected() }.onFailure { Log.e(TAG, "speech model load failed", it) }
        }
        scope.launch(llmDispatcher) {
            runCatching { cleanerForSelected() }.onFailure { Log.e(TAG, "cleanup model load failed", it) }
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
        if (!hasMicPermission() || !models.isReady(models.selected)) return
        status.text = "Listening…"
        val gate = SpeechGate.create(assets)
        recorder = Recorder(gate, onSpeechEnd = {
            // Mic thread → main. Only hands-free ends on a pause; holding keeps listening.
            mic.post { if (handsFree) finishDictation() }
        }).also { it.start() }
    }

    private fun finishDictation() {
        val rec = recorder ?: return
        recorder = null
        handsFree = false
        mic.text = "Hold to talk"
        val audio = rec.stop()
        if (!rec.heardSpeech) { status.text = "Didn't hear any speech"; return }
        if (audio.size < Recorder.SAMPLE_RATE / 4) { status.text = "Too short"; return }
        status.text = if (engine == null || engineModel != models.selected) "Loading model, then transcribing…" else "Transcribing…"
        val t0 = SystemClock.elapsedRealtime()
        scope.launch {
            val raw = try {
                withContext(asrDispatcher) {
                    engineForSelected().transcribe(audio)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "transcription failed", t)
                status.text = "Transcription failed: ${t.message ?: t.javaClass.simpleName}"
                return@launch
            }
            val asrMs = SystemClock.elapsedRealtime() - t0
            val useLlm = models.selectedCleanup?.let { models.isReady(it) } == true && raw.isNotBlank()
            if (useLlm) status.text = "Cleaning up…"
            val t1 = SystemClock.elapsedRealtime()
            val cleaned = try {
                withContext(llmDispatcher) { cleanerForSelected().clean(raw) }
            } catch (t: Throwable) {
                Log.e(TAG, "cleanup failed, using rules", t)
                RulesCleaner.clean(raw)
            }
            val cleanMs = SystemClock.elapsedRealtime() - t1
            val ms = SystemClock.elapsedRealtime() - t0
            if (cleaned.isNotEmpty()) {
                val text = withLeadingSpace(cleaned)
                currentInputConnection?.commitText(text, 1)
                lastRaw = withLeadingSpace(raw.trim()); lastInserted = text
            }
            status.text = if (cleaned.isEmpty()) "Heard nothing · ${ms} ms"
                else if (useLlm) "Done in ${ms} ms · speech ${asrMs} + cleanup ${cleanMs} · ${audio.size / Recorder.SAMPLE_RATE}s audio"
                else "Done in ${ms} ms · ${audio.size / Recorder.SAMPLE_RATE}s audio"
            Log.i(TAG, "latency=${ms}ms asr=${asrMs}ms cleanup=${cleanMs}ms audio=${audio.size}samples")
        }
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

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "OfflineFlowIME"
        private const val TAP_MS = 350L
    }
}
