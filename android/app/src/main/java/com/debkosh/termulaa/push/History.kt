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
)

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

    @Synchronized
    fun clear() = save(emptyList())

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
    }
}
