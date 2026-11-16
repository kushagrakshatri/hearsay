package dev.offlineflow.telemetry

import android.app.Application
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Message

/**
 * The Sentry side of [Telemetry], compiled into testing builds only (builds made with
 * -PnoTelemetry use the no-op twin in src/notelemetry and carry no Sentry code at all).
 * Called on Telemetry's background thread, never on the main or model threads.
 */
internal object Backend {
    const val COMPILED_IN = true

    fun start(app: Application, dsn: String, release: String, environment: String, enabled: () -> Boolean) {
        SentryAndroid.init(app) { o ->
            o.setDsn(dsn)
            o.setRelease(release)
            o.setEnvironment(environment)
            // No IP address, user or request data. Dictated text rides only in the dictation events' extras.
            o.setSendDefaultPii(false)
            o.setAnrEnabled(true)
            o.setEnableNdk(true) // native crashes in llama.cpp / whisper.cpp / Parakeet / ONNX Runtime / LiteRT-LM
            o.setAttachScreenshot(false)
            o.setAttachViewHierarchy(false)
            o.setAttachThreads(false)
            o.setMaxBreadcrumbs(100)
            // The Settings switch: turning it off stops everything queued from here on.
            o.setBeforeSend { event, _ -> if (enabled()) event else null }
        }
    }

    fun stop() = Sentry.close()

    fun breadcrumb(category: String, message: String, data: Map<String, String>) {
        Sentry.addBreadcrumb(Breadcrumb().apply {
            setCategory(category)
            setMessage(message)
            setType("info")
            setLevel(SentryLevel.INFO)
            data.forEach { (k, v) -> setData(k, v) }
        })
    }

    fun event(
        text: String, severity: Telemetry.Level, error: Throwable?,
        tags: Map<String, String>, contexts: Map<String, Map<String, Any>>, extras: Map<String, Any>,
        fingerprint: List<String>?,
    ) {
        val e = (if (error != null) SentryEvent(error) else SentryEvent()).apply {
            setLevel(when (severity) {
                Telemetry.Level.INFO -> SentryLevel.INFO
                Telemetry.Level.WARNING -> SentryLevel.WARNING
                Telemetry.Level.ERROR -> SentryLevel.ERROR
            })
            setMessage(Message().apply { setMessage(text) })
            tags.forEach { (k, v) -> setTag(k, v) }
            extras.forEach { (k, v) -> setExtra(k, v) }
            if (fingerprint != null) setFingerprints(fingerprint)
        }
        contexts.forEach { (k, v) -> e.contexts.put(k, v) }
        Sentry.captureEvent(e)
    }
}
