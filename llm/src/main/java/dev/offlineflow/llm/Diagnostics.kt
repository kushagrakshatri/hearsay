package dev.offlineflow.llm

/**
 * Where the LLM module reports engine trouble it recovers from (a backend that wouldn't start, a
 * prompt copy that failed), so the app can forward it to its testing diagnostics. The module has no
 * telemetry of its own; with no [sink] set (public builds) every call is a no-op.
 */
object Diagnostics {
    fun interface Sink {
        /** [error]: the recovered failure, or null for a plain state change (a breadcrumb). */
        fun report(what: String, error: Throwable?, data: Map<String, String>)
    }

    @Volatile var sink: Sink? = null

    fun problem(what: String, error: Throwable?, data: Map<String, String> = emptyMap()) {
        runCatching { sink?.report(what, error, data) }
    }

    fun step(what: String, data: Map<String, String> = emptyMap()) {
        runCatching { sink?.report(what, null, data) }
    }
}
