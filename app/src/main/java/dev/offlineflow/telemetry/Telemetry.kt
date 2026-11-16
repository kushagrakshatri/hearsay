package dev.offlineflow.telemetry

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import dev.offlineflow.BuildConfig
import dev.offlineflow.Prefs
import java.util.concurrent.Executors

/**
 * Testing-build diagnostics (Sentry): crashes, ANRs, native crashes, engine fallbacks, and one
 * event per dictation with its timings and text. Sends only when the build was given a DSN
 * (HEARSAY_SENTRY_DSN at build time) and Settings → "Send test logs to developer" is on. Builds
 * made with -PnoTelemetry compile all of it out (no Sentry code in the APK).
 *
 * Every call returns at once: the work runs on one low-priority background thread, so dictation
 * never waits on it and a failure here never reaches the caller.
 */
object Telemetry {
    private const val TAG = "HearsayTelemetry"

    enum class Level { INFO, WARNING, ERROR }

    /** Compiled in and given a DSN: the Settings switch is offered. */
    val available: Boolean get() = BuildConfig.TELEMETRY && Backend.COMPILED_IN && BuildConfig.SENTRY_DSN.isNotEmpty()

    @Volatile private var on = false
    @Volatile private var started = false
    private lateinit var app: Application

    private val worker by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "hearsay-telemetry").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    /** From Application.onCreate. Starts Sentry right away (so a crash during startup is caught) when the switch is on. */
    fun init(a: Application) {
        if (!available) return
        app = a
        on = Prefs.sendTestLogs(a)
        if (on) start()
        dev.offlineflow.llm.Diagnostics.sink = dev.offlineflow.llm.Diagnostics.Sink { what, error, data ->
            if (error != null) problem(what, error, data) else step("llm", what, data)
        }
    }

    /** The Settings switch. Off stops sending at once (queued events are dropped) and shuts Sentry down. */
    fun setEnabled(enabled: Boolean) {
        if (!available) return
        on = enabled
        if (enabled) { if (!started) start() }
        else if (started) { started = false; runCatching { worker.execute { runCatching { Backend.stop() } } } }
    }

    private fun start() {
        started = true
        try {
            val release = "${app.packageName}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic")
            // CI's screenshot tour and demo footage run the debug APK on an emulator: keep them apart from the phone.
            val env = (if (BuildConfig.DEBUG) "debug" else "release") + if (emulator) "-emulator" else ""
            Backend.start(app, BuildConfig.SENTRY_DSN, release, env) { on }
        } catch (t: Throwable) {
            started = false
            Log.w(TAG, "couldn't start diagnostics", t)
        }
    }

    /** A state change worth seeing before a crash or fallback (model loaded, engine switched). */
    fun step(category: String, message: String, data: Map<String, String> = emptyMap()) =
        post { Backend.breadcrumb(category, message, data) }

    /** An engine failure Hearsay recovered from (GPU → CPU, own engine → sherpa, a redone live piece…). */
    fun problem(what: String, error: Throwable?, data: Map<String, String> = emptyMap(), level: Level = Level.WARNING) =
        post {
            Backend.breadcrumb("fallback", what, data)
            Backend.event(what, level, error, data + ("fallback" to what.substringBefore(':')), emptyMap(), emptyMap(), listOf("fallback", what))
        }

    /** One finished dictation; see [DictationReport] for the fields. */
    fun dictation(f: DictationFacts) = post {
        val x = DictationReport.fields(f)
        Backend.breadcrumb("dictation", "dictation ${f.id}", mapOf("total_ms" to "${f.totalMs}"))
        Backend.event("dictation", Level.INFO, null, x.tags, x.contexts, x.extras, listOf("dictation"))
    }

    /**
     * What happened to a dictation after it went in: [kind] "undo to raw" (the undo key / pill) or
     * "edited" (the user changed the inserted words; [inserted] → [now]). Same dictation_id tag.
     */
    fun followUp(id: String?, kind: String, inserted: String, now: String) = post {
        Backend.event("dictation $kind", Level.INFO, null,
            mapOf("dictation_id" to (id ?: "unknown"), "follow_up" to kind), emptyMap(),
            mapOf("inserted_text" to inserted.take(DictationReport.TEXT_MAX), "text_now" to now.take(DictationReport.TEXT_MAX)),
            listOf("dictation", kind))
    }

    /** Device facts for the dictation tags: "Google Pixel 9", SoC, and the GPU name once Hearsay has read it. */
    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    fun soc(): String = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
    fun gpu(c: Context): String = Prefs.gpuRenderer(c).ifBlank { dev.offlineflow.llm.Speculation.detectedRenderer ?: "" }

    fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

    private fun post(block: () -> Unit) {
        if (!available || !on || !started) return
        try { worker.execute { runCatching(block).onFailure { Log.w(TAG, "diagnostics call failed", it) } } }
        catch (t: Throwable) { Log.w(TAG, "diagnostics queue refused", t) }
    }
}
