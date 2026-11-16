package dev.offlineflow.telemetry

import android.app.Application

/** Public builds (-PnoTelemetry): no Sentry dependency, nothing is ever sent. Same shape as src/telemetry's Backend. */
@Suppress("UNUSED_PARAMETER")
internal object Backend {
    const val COMPILED_IN = false

    fun start(app: Application, dsn: String, release: String, environment: String, enabled: () -> Boolean) {}
    fun stop() {}
    fun breadcrumb(category: String, message: String, data: Map<String, String>) {}
    fun event(
        text: String, severity: Telemetry.Level, error: Throwable?,
        tags: Map<String, String>, contexts: Map<String, Map<String, Any>>, extras: Map<String, Any>,
        fingerprint: List<String>?,
    ) {}
}
