package com.debkosh.termulaa.push

/**
 * Handles one ntfy event, from the stream or the poll: decrypt, then show,
 * replace or cancel, and maybe speak. Anything that does not decrypt with
 * this phone's key is dropped: the topic name is the only lock on ntfy.sh.
 */
class Inbox(
    private val store: PushStore,
    private val show: (PushMessage) -> Unit,
    private val cancel: (String) -> Unit,
    private val speak: (PushMessage) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** Returns what it did, for the log and the tests. */
    fun handle(line: String): String {
        val ev = Wire.event(line) ?: return "not an event"
        if (ev.event != "message" || ev.message == null) return "ignored ${ev.event}"
        if (!store.firstSight(ev.id)) return "seen"
        store.lastId = ev.id
        val identity = store.identity ?: return "not set up"
        val m = try {
            Wire.message(PushCrypto.open(identity, ev.message))
        } catch (_: Exception) {
            return "dropped: not for this key"
        }
        m.cancel?.let {
            cancel(it)
            return "cancelled"
        }
        if (m.level !in LEVELS || m.title.isNullOrBlank()) return "dropped: no level or title"
        show(m)
        // A message replayed after hours offline is shown, never spoken.
        val fresh = ev.time == 0L || now() - ev.time <= FRESH_SECONDS
        val wanted = when (m.level) {
            "decision" -> store.speakDecision
            "blocker" -> store.speakBlocker
            else -> store.speakInfo
        }
        if (fresh && wanted) speak(m)
        return "shown"
    }

    companion object {
        val LEVELS = setOf("info", "decision", "blocker")
        const val FRESH_SECONDS = 5 * 60L
    }
}
