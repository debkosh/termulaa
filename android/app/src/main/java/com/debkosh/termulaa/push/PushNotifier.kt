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
import androidx.core.content.ContextCompat
import com.debkosh.termulaa.MainActivity
import com.debkosh.termulaa.R

/**
 * One channel per level, so Android's own settings silence each, plus the
 * minimised channel for the listening service's permanent notification.
 */
class PushNotifier(private val context: Context) {

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

    fun show(m: PushMessage) {
        val channel = channelFor(m.level ?: return) ?: return
        if (!canPost()) return
        val title = m.title ?: return
        val from = m.from?.takeIf { it.isNotBlank() } ?: "termulaa"
        val text = if (m.body.isNullOrBlank()) title else "$title\n\n${m.body}"
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_termulaa)
            .setContentTitle(from)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (m.level == "info") Notification.CATEGORY_STATUS else Notification.CATEGORY_REMINDER)
            .setPriority(if (m.level == "info") NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent(m))
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        nm.notify(tag(m.key ?: from), ID, n)
    }

    fun cancel(key: String) = nm.cancel(tag(key), ID)

    fun listening(text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_LISTEN)
            .setSmallIcon(R.drawable.ic_stat_termulaa)
            .setContentTitle("Listening for session notifications")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

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
        const val ID_LISTEN = 101
    }
}
