package com.debkosh.termulaa.push

/**
 * Handles one ntfy event, from the stream or the poll: decrypt, then show,
 * replace or cancel, and maybe speak. Anything that does not decrypt with
 * this phone's key is dropped: the topic name is the only lock on ntfy.sh.
 */
class Inbox(
    private val store: PushStore,
    /** Posts the notification; silent when the voice will say it, so there is no ping. */
    private val show: (PushMessage, Boolean) -> Unit,
    private val cancel: (String) -> Unit,
    private val speak: (PushMessage) -> Unit,
    /** Whether the voice may speak now (earphones, Do Not Disturb, mute, channel on). */
    private val canSpeak: (PushMessage) -> Boolean = { true },
    private val record: (HistoryEntry) -> Unit = {},
    /** Shows it on screen over other apps, when it arrives fresh. */
    private val popup: (PushMessage, Boolean) -> Unit = { _, _ -> },
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
        // A message replayed after hours offline is shown, never spoken.
        val fresh = ev.time == 0L || now() - ev.time <= FRESH_SECONDS
        val wanted = when (m.level) {
            "decision" -> store.speakDecision
            "blocker" -> store.speakBlocker
            else -> store.speakInfo
        }
        val voiced = fresh && wanted && canSpeak(m)
        // Recorded first: the notification shows the sender's recent messages.
        record(HistoryEntry(id = ev.id, at = if (ev.time > 0) ev.time else now(), level = m.level!!,
            from = m.from ?: "", title = m.title!!, body = m.body ?: "", link = m.link ?: "",
            key = m.key ?: ""))
        show(m, voiced)
        if (fresh) popup(m, voiced)
        if (voiced) speak(m)
        return "shown"
    }

    companion object {
        val LEVELS = setOf("info", "decision", "blocker")
        const val FRESH_SECONDS = 5 * 60L
    }
}
