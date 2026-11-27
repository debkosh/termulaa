package com.debkosh.termulaa.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debkosh.termulaa.AppGraph
import com.debkosh.termulaa.push.Avatar
import com.debkosh.termulaa.push.Conversation
import com.debkosh.termulaa.push.History
import com.debkosh.termulaa.push.HistoryEntry
import java.text.DateFormat
import java.util.Date

/**
 * The main page's first tab: one row per sender, as a messaging app lists
 * people (each session is one person), newest conversation first. A row
 * opens that sender's messages.
 */
@Composable
fun NotificationsList(nowMillis: Long) {
    val context = LocalContext.current
    val graph = AppGraph.get(context)
    val entries by graph.pushHistory.entries.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val conversations = History.conversations(entries)

    val selected = open?.let { s -> conversations.firstOrNull { it.sender == s } }
    if (selected != null) {
        BackHandler { open = null }
        Thread(selected, nowMillis, onBack = { open = null })
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (conversations.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (graph.pushStore.enabled) {
                        "No notifications yet. Each session that sends one appears here, like a contact."
                    } else {
                        "Session notifications are off. Turn them on in Settings > Notifications."
                    },
                    color = Palette.Dim, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            item(key = "head") {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
                    Text(
                        "${conversations.size} sessions · ${entries.size} messages",
                        color = Palette.Dim, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { graph.pushHistory.clear() }) { Text("Clear all", color = Palette.Dim) }
                }
            }
        }
        items(conversations, key = { it.sender }) { c ->
            ConversationRow(c, nowMillis) { open = c.sender }
            HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(start = 72.dp))
        }
    }
}

@Composable
private fun AvatarImage(sender: String, size: Int) {
    val bitmap = remember(sender) { Avatar.bitmap(sender, size * 3).asImageBitmap() }
    Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp))
}

@Composable
private fun ConversationRow(c: Conversation, nowMillis: Long, onTap: () -> Unit) {
    val latest = c.latest
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        AvatarImage(c.sender, 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Avatar.name(c.sender), color = Palette.Text, style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Avatar.project(c.sender).takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, color = Palette.Dim, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Text(whenText(latest.at * 1000, nowMillis), color = Palette.Dim,
                    style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelDot(latest.level)
                Text(latest.title, color = if (c.unread > 0) Palette.Text else Palette.Dim,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (latest.taken) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f))
                if (c.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(contentAlignment = Alignment.Center,
                        modifier = Modifier.size(20.dp).background(Palette.Green, CircleShape)) {
                        Text("${c.unread}", color = Palette.Bg, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelDot(level: String) {
    if (level == "info") return
    Box(Modifier.padding(end = 6.dp).size(8.dp).background(levelColor(level), CircleShape))
}

private fun levelColor(level: String): Color = when (level) {
    "decision" -> Palette.Amber
    "blocker" -> Palette.Red
    else -> Palette.Green
}

/** One sender's messages, oldest at the top, like a chat. */
@Composable
private fun Thread(c: Conversation, nowMillis: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = AppGraph.get(context)
    LaunchedEffect(c.sender, c.entries.size) { graph.pushHistory.markRead(c.sender) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Dim)
            }
            AvatarImage(c.sender, 32)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(Avatar.name(c.sender), color = Palette.Text, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Avatar.project(c.sender).takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = Palette.Dim, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (c.link.startsWith("https://")) {
                TextButton(onClick = { openUrl(context, c.link) }) { Text("Open session", color = Palette.Green) }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
            reverseLayout = true,
        ) {
            items(c.entries, key = { it.id }) { e -> Bubble(e, nowMillis) }
        }
        TextButton(onClick = { graph.pushHistory.clear(c.sender); onBack() },
            modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Clear this conversation", color = Palette.Dim)
        }
    }
}

@Composable
private fun Bubble(e: HistoryEntry, nowMillis: Long) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(0.9f)
            .clickable(enabled = e.link.startsWith("https://")) { openUrl(context, e.link) },
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = when (e.level) { "decision" -> "needs you"; "blocker" -> "blocked"; else -> "update" }
                Text(label, color = levelColor(e.level), style = MaterialTheme.typography.labelSmall)
                if (e.taken) {
                    Spacer(Modifier.width(6.dp))
                    Text("· answered", color = Palette.Dim, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                Text(whenText(e.at * 1000, nowMillis), color = Palette.Dim, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.padding(top = 4.dp))
            Text(e.title, color = if (e.taken) Palette.Dim else Palette.Text, style = MaterialTheme.typography.bodyMedium)
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
        mins < 60 -> "${mins}m"
        mins < 24 * 60 -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
    }
}
