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
import dev.offlineflow.asr.SpeechEngine
import dev.offlineflow.asr.WhisperEngine
import dev.offlineflow.audio.Recorder
import dev.offlineflow.cleanup.RulesCleaner
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.*

/**
 * M0 keyboard: hold the mic, talk, release. Raw whisper.cpp transcript, lightly
 * tidied by RulesCleaner, is committed at the cursor. "Raw" swaps the last insert
 * back to the exact transcript.
 */
class OfflineFlowIME : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val asrDispatcher = Dispatchers.Default.limitedParallelism(1)
    private lateinit var models: ModelManager
    private var engine: SpeechEngine? = null
    private var recorder: Recorder? = null

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
            !models.isReady(MODEL) -> "Open the Hearsay app to download the speech model"
            else -> "Ready · on-device"
        }
        warmUp()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        recorder?.stop(); recorder = null
    }

    override fun onDestroy() {
        scope.cancel()
        engine?.close(); engine = null
        super.onDestroy()
    }

    private fun warmUp() {
        if (engine != null || !models.isReady(MODEL)) return
        scope.launch(asrDispatcher) {
            runCatching { engine = WhisperEngine.load(models.file(MODEL)) }
                .onFailure { Log.e(TAG, "model load failed", it) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onMicTouch(v: View, e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> startDictation()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishDictation()
        }
        return true
    }

    private fun startDictation() {
        if (!hasMicPermission() || !models.isReady(MODEL)) return
        status.text = "Listening…"
        recorder = Recorder().also { it.start() }
    }

    private fun finishDictation() {
        val rec = recorder ?: return
        recorder = null
        val audio = rec.stop()
        if (audio.size < Recorder.SAMPLE_RATE / 4) { status.text = "Too short"; return }
        status.text = "Transcribing…"
        val t0 = SystemClock.elapsedRealtime()
        scope.launch {
            val raw = withContext(asrDispatcher) {
                val eng = engine ?: WhisperEngine.load(models.file(MODEL)).also { engine = it }
                eng.transcribe(audio)
            }
            val cleaned = RulesCleaner.clean(raw)
            val ms = SystemClock.elapsedRealtime() - t0
            if (cleaned.isNotEmpty()) {
                val text = withLeadingSpace(cleaned)
                currentInputConnection?.commitText(text, 1)
                lastRaw = withLeadingSpace(raw.trim()); lastInserted = text
            }
            status.text = "Done in ${ms} ms · ${audio.size / Recorder.SAMPLE_RATE}s audio"
            Log.i(TAG, "latency=${ms}ms audio=${audio.size}samples")
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
        val MODEL = ModelManager.Model.BASE_EN_Q5
    }
}
