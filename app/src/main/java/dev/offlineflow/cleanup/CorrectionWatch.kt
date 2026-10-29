package dev.offlineflow.cleanup

/**
 * Remembers the last thing Hearsay inserted into a field, so once the user is done fixing it
 * we can see what they changed. Only the inserted stretch is compared, never the rest of the field.
 */
class CorrectionWatch(private val maxAgeMs: Long = 5 * 60_000L) {
    private var target: Any? = null
    private var before = ""
    private var piece = ""
    private var after = ""
    private var at = 0L

    val watching: Any? get() = target

    /** [full] is the field's text right after inserting [piece] at [start]. */
    fun begin(field: Any, full: String, start: Int, piece: String, now: Long) {
        if (start < 0 || start + piece.length > full.length || full.substring(start, start + piece.length) != piece) { clear(); return }
        this.target = field; this.piece = piece; at = now
        before = full.substring(0, start); after = full.substring(start + piece.length)
    }

    /**
     * What the inserted stretch reads as in [current], or null when it's unchanged, can't be
     * found any more (the user edited around it), or it's too old.
     */
    fun edited(field: Any, current: String, now: Long): Pair<String, String>? {
        if (target == null || target != field) return null
        if (now - at > maxAgeMs) { clear(); return null }
        // Typing after the insert at the end of the field is fine: it's just more words.
        val afterOk = after.isEmpty() || current.endsWith(after)
        if (!current.startsWith(before) || !afterOk || current.length < before.length + after.length) return null
        val middle = current.substring(before.length, current.length - after.length)
        return if (middle == piece) null else piece to middle
    }

    fun clear() { target = null; piece = ""; before = ""; after = "" }
}
