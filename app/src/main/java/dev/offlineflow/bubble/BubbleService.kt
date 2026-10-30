package dev.offlineflow.bubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M
import dev.offlineflow.Prefs
import dev.offlineflow.R
import dev.offlineflow.audio.Recorder
import dev.offlineflow.audio.SpeechGate
import dev.offlineflow.cleanup.Commands
import dev.offlineflow.cleanup.Continuity
import dev.offlineflow.cleanup.CorrectionWatch
import dev.offlineflow.cleanup.ScreenNames
import dev.offlineflow.cleanup.Words
import dev.offlineflow.cleanup.LlmCleaner
import dev.offlineflow.cleanup.Snippets
import dev.offlineflow.cleanup.Style
import dev.offlineflow.cloud.CloudClient
import dev.offlineflow.cloud.CloudText
import dev.offlineflow.core.Brain
import dev.offlineflow.core.Learning
import dev.offlineflow.ime.MicButton
import dev.offlineflow.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating mic. When a text field is focused and a keyboard (any keyboard but Hearsay's)
 * is up, a round mic floats just above it. Hold to talk, tap for hands-free, drag to move.
 * Text goes into the focused field through accessibility (set text, or paste as a fallback).
 *
 * It reads the focused field (its app, input type, text and selection) to insert at the cursor
 * and pick the writing style. When "names on screen" is on, it also reads the visible text of
 * the app you're dictating into at the moment you stop talking, keeps only the names in it, and
 * uses them for that one dictation. It watches the field after inserting so a word you fix
 * can be learned. Nothing it reads is stored except the learned word.
 */
class BubbleService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var models: ModelManager
    private lateinit var wm: WindowManager

    private var root: LinearLayout? = null
    private lateinit var mic: MicButton
    private lateinit var pill: TextView
    private var params: WindowManager.LayoutParams? = null
    private var imeTop = 0

    private var recorder: Recorder? = null
    private var handsFree = false
    private var busy = false
    private var downAt = 0L
    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0
    private var dragging = false
    private var lastInserted: String? = null
    private var idleJob: Job? = null
    private var warmedFor: String? = null
    private var lastEditable: AccessibilityNodeInfo? = null
    // Undo to what you actually said: the raw words for the last cleaned insert.
    private var lastRaw: String? = null
    // Set in onDestroy: a check still queued on the main thread must not put up a bubble nobody owns.
    private var destroyed = false
    private var undoable = false
    // A voice-activity gate made ahead of time, so pressing the mic doesn't stall on loading it.
    @Volatile private var spareGate: SpeechGate? = null
    private var pendingX = 0; private var pendingY = 0
    private var framePosted = false
    private var snap: android.animation.ValueAnimator? = null
    private var lastWhy: String? = null
    private val watch = CorrectionWatch()
    private var watchedText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected ${System.identityHashCode(this)}")
        models = ModelManager(this)
        wm = getSystemService(WindowManager::class.java)
        check()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            // The user typing in the field we just dictated into: note it, learn once they've paused.
            val w = watch.watching ?: return
            val src = runCatching { event.source }.getOrNull() ?: return
            if (src != w || src.isPassword) return
            watchedText = current(src).first
            main.removeCallbacks(learnRunnable)
            main.postDelayed(learnRunnable, LEARN_AFTER_MS)
            return
        }
        // Remember the last text box the user tapped or focused, for apps that don't report input focus.
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED || event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            runCatching { event.source }.getOrNull()?.let { if (looksEditable(it)) lastEditable = it }
        }
        // Coalesce bursts of focus / window events into one check.
        main.removeCallbacks(checkRunnable)
        main.postDelayed(checkRunnable, 120)
    }

    override fun onInterrupt() {}

    private val checkRunnable = Runnable { check() }
    private val learnRunnable = Runnable { learnNow() }

    /** Compares what we inserted with what the user left there, and learns any fixed names. */
    private fun learnNow() {
        main.removeCallbacks(learnRunnable)
        val w = watch.watching ?: return
        val text = watchedText ?: run { watch.clear(); return }
        val change = watch.edited(w, text, SystemClock.elapsedRealtime())
        watch.clear(); watchedText = null
        if (change == null) return
        scope.launch {
            val added = withContext(Dispatchers.IO) { runCatching { Learning.learn(this@BubbleService, change.first, change.second) }.getOrDefault(emptyList()) }
            if (added.isEmpty()) return@launch
            val msg = Learning.message(added)
            if (root?.visibility == View.VISIBLE) say(msg)
            else android.widget.Toast.makeText(this@BubbleService, "Hearsay: $msg", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Names and jargon visible in the app being dictated into; empty when the user turned it off. */
    private fun screenNames(node: AccessibilityNodeInfo): List<String> {
        if (!Prefs.screenContext(this)) return emptyList()
        val root = runCatching { node.window?.root }.getOrNull() ?: rootInActiveWindow ?: return emptyList()
        val texts = ArrayList<String>()
        var seen = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (seen++ > 800 || depth > 30) return
            if (n.isPassword) return
            if (n.isVisibleToUser) {
                n.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
                n.contentDescription?.toString()?.takeIf { it.isNotBlank() && it.length < 120 }?.let { texts += it }
            }
            for (i in 0 until minOf(n.childCount, 80)) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        val common = Words.common(this)
        return ScreenNames.from(texts, { w -> w.lowercase().replace('\u2019', '\'') in common })
    }

    /** Shows the bubble above the keyboard when there's a field to dictate into, hides it otherwise. */
    private fun check() {
        if (recorder != null || busy || dragging) return
        val target = runCatching { target() }.getOrNull()
        if (watch.watching != null && target != watch.watching) learnNow()
        val ime = runCatching { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrNull()
        val why = when {
            ime == null -> null // no keyboard up: nothing to explain
            !Prefs.bubbleOn(this) -> "the floating mic is switched off"
            hearsayKeyboardActive() -> "the Hearsay keyboard is in use, so its own mic shows instead"
            !hasMic() -> "the microphone isn't allowed yet"
            !(models.isReady(models.selected) || CloudClient.speechOn(this)) -> "no voice model is downloaded"
            target == null -> "the app didn't report a text box it lets Hearsay type into" +
                (if (focusIsPassword()) " (it's a password box)" else "")
            else -> ""
        }
        if (why != null) note(why)
        if (target == null || ime == null || !Prefs.bubbleOn(this) || hearsayKeyboardActive() || !hasMic() ||
            !(models.isReady(models.selected) || CloudClient.speechOn(this))) {
            hide(); return
        }
        // Some keyboards report a window that starts below their toolbar; their content root doesn't.
        val r = Rect(); ime.getBoundsInScreen(r)
        var top = r.top
        runCatching { ime.root?.let { top = minOf(top, visibleTop(it, resources.displayMetrics.heightPixels)) } }
        imeTop = top
        show()
        warm(target)
    }

    /** Top of the keyboard's visible content (keys, toolbar), skipping full-height transparent containers. */
    private fun visibleTop(root: AccessibilityNodeInfo, screenH: Int): Int {
        var best = Int.MAX_VALUE
        val c = Rect()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            n.getBoundsInScreen(c)
            if (n.isVisibleToUser && c.height() in 1 until screenH / 2 && c.top > screenH * 3 / 10) best = minOf(best, c.top)
            if (depth < 5) for (i in 0 until minOf(n.childCount, 40)) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return best
    }

    /**
     * The focused, editable, non-password field, or null. Some apps (web views, Flutter, custom
     * editors) don't report input focus to the system, so this also checks each window's own focus
     * and the text box the user last tapped.
     */
    private fun target(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { n -> if (looksEditable(n)) return n.takeUnless { it.isPassword } }
        runCatching {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val f = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                if (looksEditable(f)) return f.takeUnless { it.isPassword }
            }
        }
        lastEditable?.let { n ->
            if (runCatching { n.refresh() }.getOrDefault(false) && n.isFocused && n.isVisibleToUser && looksEditable(n)) return n.takeUnless { it.isPassword }
        }
        return null
    }

    private fun looksEditable(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || n.className?.toString()?.endsWith("EditText") == true ||
            n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    private fun focusIsPassword() = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isPassword == true }.getOrDefault(false)

    /** Saves why the mic did or didn't show in the app in front, for Settings to explain. */
    private fun note(why: String) {
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return
        if (pkg == packageName && why.isEmpty()) return
        val line = "$pkg|$why"
        if (line == lastWhy) return
        lastWhy = line
        Prefs.setBubbleWhy(this, line)
    }

    private fun hearsayKeyboardActive() =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ the bubble

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (destroyed) return
        idleJob?.cancel(); idleJob = null
        if (root == null) {
            val t = DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(this, R.style.Theme_Hearsay))
            fun c(attr: Int) = MaterialColors.getColor(t, attr, Color.GRAY)
            mic = MicButton(t).apply {
                idleColor = c(M.attr.colorPrimary); iconColor = c(M.attr.colorOnPrimary)
                activeColor = c(M.attr.colorError); busyColor = c(M.attr.colorPrimary)
                elevation = dp(6).toFloat()
                setOnTouchListener(::onTouch)
            }
            pill = TextView(t).apply {
                textSize = 13f; setTextColor(c(M.attr.colorOnSurface)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(220); visibility = View.GONE; elevation = dp(4).toFloat()
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { if (undoable) undoToRaw() }
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(c(M.attr.colorSurfaceContainerHighest)) }
            }
            root = LinearLayout(t).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(8) })
                addView(mic, LinearLayout.LayoutParams(dp(SIZE_DP), dp(SIZE_DP)))
            }
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.END }
            place()
            runCatching { wm.addView(root, params) }.onFailure { Log.e(TAG, "overlay failed", it); root = null; return }
        } else {
            place(); runCatching { wm.updateViewLayout(root, params) }
        }
        root?.visibility = View.VISIBLE
        prepGate()
        reportMicSpot()
    }

    /** Debug builds only: where the mic sits on screen, so the launch-video script can press it. */
    private fun reportMicSpot() {
        val dir = dev.offlineflow.audio.Recorder.demoDir ?: return
        mic.postDelayed({
            val loc = IntArray(2); mic.getLocationOnScreen(loc)
            runCatching { java.io.File(dir, "bubble.txt").writeText("${loc[0] + mic.width / 2} ${loc[1] + mic.height / 2}") }
        }, 400)
    }

    private fun prepGate() {
        if (spareGate != null) return
        scope.launch(Dispatchers.Default) { if (spareGate == null) spareGate = SpeechGate.create(assets) }
    }

    /** x from the right edge; y relative to the keyboard's top so it rides above any keyboard height. */
    private fun place() {
        val p = params ?: return
        p.x = Prefs.bubbleX(this).takeIf { it >= 0 } ?: dp(8)
        val dy = Prefs.bubbleDy(this).takeIf { it != Int.MIN_VALUE } ?: -dp(SIZE_DP + 20 + TOOLBAR_DP)
        p.y = (imeTop + dy).coerceIn(dp(48), (imeTop - dp(SIZE_DP)).coerceAtLeast(dp(48)))
    }

    private fun hide() {
        val r = root ?: return
        if (r.visibility != View.VISIBLE) return
        r.visibility = View.GONE
        // Hidden: free the cleanup LLM after a minute and the speech model after five.
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(60_000); Brain.releaseLlm()
            delay(240_000); Brain.releaseAsr()
        }
    }

    private fun say(msg: String?, holdMs: Long = 2500) {
        if (root == null) return
        undoable = false
        main.removeCallbacks(clearPill)
        if (msg == null) { pill.visibility = View.GONE; return }
        pill.text = msg; pill.visibility = View.VISIBLE
        if (holdMs > 0) main.postDelayed(clearPill, holdMs)
    }
    private val clearPill = Runnable { undoable = false; if (root != null) pill.visibility = View.GONE }

    /** Swaps the last cleaned insert for the words exactly as heard (the keyboard's undo key, for the bubble). */
    private fun undoToRaw() {
        undoable = false
        val ins = lastInserted ?: return
        val raw = lastRaw ?: return
        val node = target() ?: run { say("Tap the text box first"); return }
        node.refresh()
        val (text, sel) = current(node)
        if (!text.substring(0, sel.first).endsWith(ins)) { say("The text changed, so I left it alone"); return }
        val start = sel.first - ins.length
        val piece = if (ins.startsWith(" ")) " $raw" else raw
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.substring(0, start) + piece + text.substring(sel.last))
        })
        if (!ok) { say("This app won't let me swap it"); return }
        val cursor = start + piece.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        lastInserted = piece; lastRaw = null; watch.clear()
        say("Back to what you said")
    }

    private fun warm(node: AccessibilityNodeInfo) {
        val pkg = node.packageName?.toString()
        if (warmedFor == pkg) return
        warmedFor = pkg
        val style = Style.forApp(this, pkg, node.inputType)
        if (models.isReady(models.selected) && !CloudClient.speechOn(this)) scope.launch(Brain.asr) {
            runCatching { Brain.engine(models) }.onFailure { Log.e(TAG, "speech warm-up failed", it) }
        }
        if (!CloudText.on(this) && style != Style.VERBATIM) scope.launch(Brain.llm) {
            runCatching { (Brain.cleaner(models, dev.offlineflow.cleanup.Dictionary.load(this@BubbleService)) as? LlmCleaner)?.warm(style) }
                .onFailure { Log.e(TAG, "cleanup warm-up failed", it) }
        }
    }

    // ------------------------------------------------------------------ touch: hold, tap, drag

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (busy) return true
                if (handsFree) { finish(); return true }
                downAt = SystemClock.elapsedRealtime(); downX = e.rawX; downY = e.rawY
                snap?.cancel(); snap = null
                startX = params?.x ?: 0; startY = params?.y ?: 0
                dragging = false
                startListening()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !handsFree && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                    dragging = true; cancelListening()
                    mic.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                }
                if (dragging) {
                    // One window move per frame, however many touch events arrive.
                    pendingX = (startX - (e.rawX - downX)).toInt().coerceAtLeast(0)
                    pendingY = (startY + (e.rawY - downY)).toInt().coerceAtLeast(dp(48))
                    if (!framePosted) {
                        framePosted = true
                        android.view.Choreographer.getInstance().postFrameCallback {
                            framePosted = false
                            params?.let { p -> p.x = pendingX; p.y = pendingY; runCatching { wm.updateViewLayout(root, p) } }
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    mic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    params?.let { p -> p.x = pendingX; p.y = pendingY }
                    snapToEdge()
                    return true
                }
                if (recorder == null || handsFree) return true
                if (e.actionMasked == MotionEvent.ACTION_UP && SystemClock.elapsedRealtime() - downAt < TAP_MS) {
                    handsFree = true; mic.state = MicButton.State.HANDS_FREE
                    say("Listening\u2026 pause or tap to stop", 0)
                } else finish()
            }
        }
        return true
    }

    private fun startListening() {
        if (!hasMic()) { say("Open Hearsay to allow the microphone"); return }
        learnNow()
        mic.state = MicButton.State.LISTENING
        buzz()
        val gate = spareGate ?: SpeechGate.create(assets)
        spareGate = null; prepGate()
        recorder = Recorder(gate, endSilenceMs = Prefs.pauseMs(this), onSpeechEnd = {
            mic.post { if (handsFree) finish() }
        }, onLevel = { level -> mic.post { mic.push(level) } }).also { it.start() }
    }

    /** Glides to the nearer side of the screen after a drag, then saves the spot. */
    private fun snapToEdge() {
        val p = params ?: run { dragging = false; return }
        val w = root?.width ?: 0
        val screenW = resources.displayMetrics.widthPixels
        val margin = dp(8)
        val target = if (p.x + w / 2 > screenW / 2) (screenW - w - margin).coerceAtLeast(margin) else margin
        snap = android.animation.ValueAnimator.ofInt(p.x, target).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a -> p.x = a.animatedValue as Int; runCatching { wm.updateViewLayout(root, p) } }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dragging = false; snap = null
                    Prefs.setBubblePos(this@BubbleService, p.x, p.y - imeTop)
                }
            })
            start()
        }
    }

    private fun cancelListening() {
        // Stopping waits on the mic thread; do it off the main thread so the drag stays smooth.
        recorder?.let { r -> scope.launch(Dispatchers.Default) { r.stop() } }
        recorder = null; handsFree = false
        mic.state = MicButton.State.IDLE; say(null)
    }

    private fun finish() {
        val rec = recorder ?: return
        recorder = null; handsFree = false
        buzz()
        val audio = rec.stop()
        if (!rec.heardSpeech || audio.size < Recorder.SAMPLE_RATE / 4) {
            mic.state = MicButton.State.IDLE
            say(if (!rec.heardSpeech) "Didn't catch that" else "Hold the mic while you talk"); return
        }
        busy = true; mic.state = MicButton.State.BUSY
        say(if (Brain.engineLoaded(models.selected)) "Writing it down\u2026" else "Warming up\u2026", 0)
        val t0 = SystemClock.elapsedRealtime()
        // Read the names on screen while the speech model works.
        val names = target()?.let { n -> scope.async(Dispatchers.Default) { runCatching { screenNames(n) }.getOrDefault(emptyList()) } }
        scope.launch {
            try {
                Log.i(TAG, "transcribe ${audio.size} samples on ${System.identityHashCode(this@BubbleService)}")
                val raw = try { Brain.transcribe(this@BubbleService, models, audio) } catch (t: Throwable) {
                    Log.e(TAG, "transcription failed", t); say("Couldn't write that down"); return@launch
                }
                val node = target() ?: run { say("Tap a text box first"); return@launch }
                node.refresh()
                val (text, sel) = current(node)
                val selected = if (sel.first != sel.last) text.substring(sel.first, sel.last) else null

                if (selected == null && Commands.isScratch(raw)) { scratch(node, text, sel); return@launch }
                if (selected != null && Commands.forSelection(raw)) {
                    if (!Brain.cleanupAvailable(this@BubbleService, models)) { say("Voice edits need cleanup on"); return@launch }
                    val instruction = Commands.instruction(raw)
                    say("${instruction.replaceFirstChar { it.uppercase() }}\u2026", 0)
                    val edited = Brain.edit(this@BubbleService, models, selected, instruction)
                        ?: run { say("Couldn't do \u201c$instruction\u201d"); return@launch }
                    put(node, text, sel, edited, spaced = false); say("Done"); return@launch
                }
                Snippets.load(this@BubbleService).match(raw)?.let { snippet ->
                    put(node, text, sel, snippet, spaced = true); watch.clear(); say("Snippet added"); return@launch
                }
                val style = Style.forApp(this@BubbleService, node.packageName?.toString(), node.inputType)
                if (style != Style.VERBATIM && Brain.cleanupAvailable(this@BubbleService, models)) say("Polishing\u2026", 0)
                val cleaned = Brain.clean(this@BubbleService, models, raw, style, names?.await().orEmpty())
                if (cleaned.text.isBlank()) { say("Didn't catch any words"); return@launch }
                val before = text.substring(0, sel.first).takeLast(2)
                val out = if (style == Style.VERBATIM) cleaned.text else Continuity.apply(cleaned.text, before)
                put(node, text, sel, out, spaced = true)
                val ms = SystemClock.elapsedRealtime() - t0
                // Demo footage comes from a slow emulator; the edit marks the trimmed wait, so leave out its timing.
                val done = if (Recorder.demoDir != null) "Done" else "Done \u00b7 " + if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000f)
                val rawText = raw.trim()
                if (style != Style.VERBATIM && out.trim() != rawText && rawText.isNotEmpty()) {
                    lastRaw = rawText
                    say("$done \u00b7 tap for original", 5000)
                    undoable = true
                } else { lastRaw = null; say(done) }
            } finally {
                busy = false
                mic.state = MicButton.State.IDLE
            }
        }
    }

    // ------------------------------------------------------------------ inserting

    /** The field's real text (not its hint) and selection; no cursor means the end. */
    private fun current(node: AccessibilityNodeInfo): Pair<String, IntRange> {
        val raw = node.text?.toString().orEmpty()
        val text = if (node.isShowingHintText || (raw.isNotEmpty() && raw == node.hintText?.toString())) "" else raw
        var s = node.textSelectionStart; var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > text.length || e > text.length) { s = text.length; e = text.length }
        return text to (minOf(s, e)..maxOf(s, e))
    }

    /** Replaces the selection (or inserts at the cursor) with [ins]; falls back to paste when set-text is refused. */
    private fun put(node: AccessibilityNodeInfo, text: String, sel: IntRange, ins: String, spaced: Boolean) {
        val before = text.substring(0, sel.first)
        val piece = if (spaced && before.isNotEmpty() && !before.last().isWhitespace()) " $ins" else ins
        val updated = before + piece + text.substring(sel.last)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        })
        if (ok) {
            val cursor = before.length + piece.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            })
        } else paste(node, piece)
        lastInserted = piece
        watch.begin(node, updated, before.length, piece, SystemClock.elapsedRealtime())
        watchedText = null
    }

    private fun paste(node: AccessibilityNodeInfo, piece: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        val old = runCatching { cm.primaryClip }.getOrNull()
        cm.setPrimaryClip(ClipData.newPlainText("Hearsay", piece))
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        // Put the user's clipboard back once the paste has landed.
        main.postDelayed({ runCatching { if (old != null) cm.setPrimaryClip(old) else cm.clearPrimaryClip() } }, 600)
    }

    private fun scratch(node: AccessibilityNodeInfo, text: String, sel: IntRange) {
        val ins = lastInserted
        if (ins != null && text.substring(0, sel.first).endsWith(ins)) {
            val start = sel.first - ins.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.substring(0, start) + text.substring(sel.last))
            })
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start)
            })
            lastInserted = null; watch.clear(); say("Removed what you just said")
        } else say("Nothing to remove")
    }

    private fun buzz() {
        if (Prefs.haptics(this) && root != null) mic.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (recorder == null && !busy && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            scope.launch { Brain.releaseLlm(); Brain.releaseAsr() }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "unbind ${System.identityHashCode(this)}")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "destroy ${System.identityHashCode(this)}", Throwable("where"))
        destroyed = true
        main.removeCallbacksAndMessages(null)
        snap?.cancel()
        spareGate?.close(); spareGate = null
        recorder?.stop(); recorder = null
        root?.let { runCatching { wm.removeViewImmediate(it) } }; root = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HearsayBubble"
        private const val TAP_MS = 350L
        private const val SIZE_DP = 60
        // Gboard and others report their window top below the suggestion toolbar; clear it by default.
        private const val TOOLBAR_DP = 48
        /** Learn a fix once the user has stopped typing in the field for this long. */
        private const val LEARN_AFTER_MS = 12_000L

        /** Whether the user switched the floating mic on in Accessibility settings. */
        fun enabled(c: Context): Boolean {
            val am = c.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            val bound = runCatching {
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.resolveInfo?.serviceInfo?.let { s -> s.packageName == c.packageName && s.name == BubbleService::class.java.name } == true }
            }.getOrDefault(false)
            if (bound) return true
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any {
                it.equals("${c.packageName}/${BubbleService::class.java.name}", true) ||
                    it.equals("${c.packageName}/.bubble.BubbleService", true)
            }
        }
    }
}
