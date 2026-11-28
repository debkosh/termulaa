package com.debkosh.termulaa.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.ContextCompat
import com.debkosh.termulaa.MainActivity
import com.debkosh.termulaa.R

/**
 * One channel per level, so Android's own settings silence each, plus the
 * minimised channel for the listening service's permanent notification.
 */
class PushNotifier(
    private val context: Context,
    private val voiceMode: () -> PushStore.VoiceMode = { PushStore.VoiceMode.IMPORTANT },
) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INFO, "Finished", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "A session finished something" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DECISION, "Needs your decision", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A session is waiting on you" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BLOCKER, "Blocked", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "A session stopped and can do nothing else"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTEN, "Listening for notifications", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Android requires this while termulaa keeps its connection open" }
        )
    }

    fun channelFor(level: String): String? = when (level) {
        "info" -> CHANNEL_INFO
        "decision" -> CHANNEL_DECISION
        "blocker" -> CHANNEL_BLOCKER
        else -> null
    }

    /** False when the channel or the whole app is silenced in Android's settings. */
    fun channelOn(channel: String): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        val c = nm.getNotificationChannel(channel) ?: return true
        return c.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Posts [m] as a message from its sender, a session shown as a person
     * with its own colour and initial, with that sender's [recent] messages
     * above it (newest first, [m] among them), like a chat.
     */
    fun show(m: PushMessage, silent: Boolean = false, recent: List<HistoryEntry> = emptyList()) {
        val channel = channelFor(m.level ?: return) ?: return
        if (!canPost()) return
        val title = m.title ?: return
        val from = m.from?.takeIf { it.isNotBlank() } ?: "termulaa"
        val person = person(from)
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        val lines = recent.ifEmpty {
            listOf(HistoryEntry(id = "", at = System.currentTimeMillis() / 1000, level = m.level ?: "info",
                from = from, title = title, body = m.body ?: ""))
        }
        lines.take(RECENT).reversed().forEach { e ->
            val text = if (e.body.isBlank()) e.title else "${e.title}\n${e.body}"
            style.addMessage(text, e.at * 1000, person)
        }
        val shortcut = shortcutFor(from, person)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_termulaa)
            .setContentTitle(from)
            .setContentText(title)
            .setStyle(style)
            .setShortcutId(shortcut)
            .setGroup(GROUP)
            .setCategory(if (m.level == "info") Notification.CATEGORY_STATUS else Notification.CATEGORY_REMINDER)
            .setPriority(if (m.level == "info") NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent(m))
            .setAutoCancel(true)
            .setSilent(silent)
            .addAction(0, "Speak this", speakIntent(m.key ?: from, Speaker.line(from, title, m.body, withBody = true)))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        nm.notify(tag(m.key ?: from), ID, n)
    }

    fun cancel(key: String) = nm.cancel(tag(key), ID)

    private fun person(sender: String): Person = Person.Builder()
        .setName(sender)
        .setKey(sender)
        .setIcon(IconCompat.createWithBitmap(Avatar.bitmap(sender, 128)))
        .build()

    /**
     * A long-lived shortcut per sender, which Android needs to show a
     * notification as a conversation (its own section, the person's face).
     */
    private fun shortcutFor(sender: String, person: Person): String {
        val id = "session:" + sender.hashCode()
        try {
            ShortcutManagerCompat.pushDynamicShortcut(
                context,
                ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(sender.take(24))
                    .setLongLabel(sender)
                    .setLongLived(true)
                    .setPerson(person)
                    .setIcon(IconCompat.createWithBitmap(Avatar.bitmap(sender, 128)))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                    .build(),
            )
        } catch (_: Exception) {
        }
        return id
    }

    fun listening(text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mode = voiceMode()
        val line = "$text · voice: " + when (mode) {
            PushStore.VoiceMode.ALL -> "all"
            PushStore.VoiceMode.IMPORTANT -> "decisions and blockers"
            PushStore.VoiceMode.NONE -> "off"
            PushStore.VoiceMode.CUSTOM -> "as set in Settings"
        }
        fun label(m: PushStore.VoiceMode, name: String) = if (m == mode) "✓ $name" else name
        return NotificationCompat.Builder(context, CHANNEL_LISTEN)
            .setSmallIcon(R.drawable.ic_stat_termulaa)
            .setContentTitle("Listening for session notifications")
            .setContentText(line)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, label(PushStore.VoiceMode.ALL, "All"), action(PushActionReceiver.VOICE_ALL))
            .addAction(0, label(PushStore.VoiceMode.IMPORTANT, "Important"), action(PushActionReceiver.VOICE_IMPORTANT))
            .addAction(0, label(PushStore.VoiceMode.NONE, "None"), action(PushActionReceiver.VOICE_NONE))
            .build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getBroadcast(
        context, name.hashCode(),
        Intent(context, PushActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "Speak this": says the message now, earphones or not. One per sender. */
    private fun speakIntent(key: String, text: String): PendingIntent = PendingIntent.getBroadcast(
        context, ("speak:$key").hashCode(),
        Intent(context, PushActionReceiver::class.java).setAction(PushActionReceiver.SPEAK)
            .putExtra(PushActionReceiver.EXTRA_KEY, key).putExtra(PushActionReceiver.EXTRA_TEXT, text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun updateListening(text: String) {
        if (canPost()) nm.notify(ID_LISTEN, listening(text))
    }

    /** The link (the session in the Claude app) when there is one, else the app. */
    private fun tapIntent(m: PushMessage): PendingIntent {
        val link = m.link?.takeIf { it.startsWith("https://") }
        val intent = if (link != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
        } else {
            Intent(context, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, (m.key ?: m.from ?: "").hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun tag(key: String) = "push:$key"

    companion object {
        const val CHANNEL_INFO = "push_info"
        const val CHANNEL_DECISION = "push_decision"
        const val CHANNEL_BLOCKER = "push_blocker"
        const val CHANNEL_LISTEN = "push_listen"
        private const val ID = 1
        private const val GROUP = "sessions"
        /** How many of a sender's messages a notification shows. */
        const val RECENT = 5
        const val ID_LISTEN = 101
    }
}
