package com.debkosh.termulaa.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** One received notification, kept to read later on the main page. */
@Serializable
data class HistoryEntry(
    val id: String,
    val at: Long, // epoch seconds, from ntfy
    val level: String,
    val from: String = "",
    val title: String,
    val body: String = "",
    val link: String = "",
    /** The sender's replace/cancel key: one per session. */
    val key: String = "",
    /** Taken back by the sender (he answered at the computer). */
    val taken: Boolean = false,
    /** Seen in the app, in its conversation. */
    val read: Boolean = false,
) {
    /** Who it is from, as a conversation is named. */
    val sender: String get() = from.ifBlank { "termulaa" }
}

/** One sender's messages, newest first: a session is one person. */
data class Conversation(val sender: String, val entries: List<HistoryEntry>) {
    val latest: HistoryEntry get() = entries.first()
    val unread: Int get() = entries.count { !it.read && !it.taken }
    /** The newest link, which opens the session. */
    val link: String get() = entries.firstOrNull { it.link.isNotEmpty() }?.link ?: ""
}

/**
 * The last [MAX] notifications, newest first, in a file in the app's
 * no-backup directory. It holds what was decrypted, so it never leaves the
 * phone.
 */
class History(private val file: File) {

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    @Synchronized
    fun add(e: HistoryEntry) {
        if (_entries.value.any { it.id == e.id }) return
        save((listOf(e) + _entries.value).take(MAX))
    }

    /** Marks everything sent under [key] as taken back. */
    @Synchronized
    fun markTaken(key: String) {
        if (key.isEmpty() || _entries.value.none { it.key == key && !it.taken }) return
        save(_entries.value.map { if (it.key == key) it.copy(taken = true) else it })
    }

    /** Marks a sender's conversation as read. */
    @Synchronized
    fun markRead(sender: String) {
        if (_entries.value.none { it.sender == sender && !it.read }) return
        save(_entries.value.map { if (it.sender == sender) it.copy(read = true) else it })
    }

    /** A sender's messages still standing (not taken back), newest first. */
    fun recent(sender: String, limit: Int): List<HistoryEntry> =
        _entries.value.filter { it.sender == sender && !it.taken }.take(limit)

    @Synchronized
    fun clear() = save(emptyList())

    @Synchronized
    fun clear(sender: String) = save(_entries.value.filter { it.sender != sender })

    private fun save(list: List<HistoryEntry>) {
        _entries.value = list
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Wire.json.encodeToString(ListSerializer(HistoryEntry.serializer()), list))
            tmp.renameTo(file)
        } catch (_: Exception) {
            // The list in memory still works; it just will not survive a restart.
        }
    }

    private fun load(): List<HistoryEntry> = try {
        if (file.exists()) Wire.json.decodeFromString(ListSerializer(HistoryEntry.serializer()), file.readText())
        else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val MAX = 200

        /** Entries grouped by sender, the conversation with the newest message first. */
        fun conversations(entries: List<HistoryEntry>): List<Conversation> =
            entries.groupBy { it.sender }.map { (s, es) -> Conversation(s, es) }
                .sortedByDescending { it.latest.at }
    }
}
