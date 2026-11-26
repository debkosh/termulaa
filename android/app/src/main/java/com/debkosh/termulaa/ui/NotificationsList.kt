package com.debkosh.termulaa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.debkosh.termulaa.AppGraph
import com.debkosh.termulaa.push.HistoryEntry
import java.text.DateFormat
import java.util.Date

/** Every notification received, newest first, in full: the main page's first tab. */
@Composable
fun NotificationsList(nowMillis: Long) {
    val context = LocalContext.current
    val graph = AppGraph.get(context)
    val entries by graph.pushHistory.entries.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        if (entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (graph.pushStore.enabled) {
                        "No notifications yet. What sessions send appears here, newest first."
                    } else {
                        "Session notifications are off. Turn them on in Settings > Session notifications."
                    },
                    color = Palette.Dim, style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            item(key = "clear") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("${entries.size} notifications", color = Palette.Dim,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { graph.pushHistory.clear() }) { Text("Clear", color = Palette.Dim) }
                }
            }
        }
        items(entries, key = { it.id }) { e ->
            NotificationCard(e, nowMillis) { if (e.link.startsWith("https://")) openUrl(context, e.link) }
        }
    }
}

@Composable
private fun NotificationCard(e: HistoryEntry, nowMillis: Long, onTap: () -> Unit) {
    val (label, color) = when (e.level) {
        "decision" -> "decision" to Palette.Amber
        "blocker" -> "blocked" to Palette.Red
        else -> "finished" to Palette.Green
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = e.link.isNotEmpty(), onClick = onTap),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Text(e.from, color = Palette.Text, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f))
                Text(whenText(e.at * 1000, nowMillis), color = Palette.Dim, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.padding(top = 6.dp))
            Text(e.title, color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
            if (e.body.isNotBlank()) {
                Spacer(Modifier.padding(top = 4.dp))
                Text(e.body, color = Palette.Dim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun whenText(at: Long, now: Long): String {
    val mins = (now - at) / 60_000
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
    }
}
