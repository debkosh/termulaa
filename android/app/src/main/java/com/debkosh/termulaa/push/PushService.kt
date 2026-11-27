package com.debkosh.termulaa.push

import android.app.AlarmManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.debkosh.termulaa.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Holds one WebSocket to the ntfy topic for as long as notifications are on.
 *
 * Keeping it alive follows ntfy's own Android app (Apache 2.0,
 * github.com/binwiederhier/ntfy-android, service/SubscriberService.kt and
 * WsConnection.kt), cut down to one topic:
 * - a `specialUse` foreground service (Android 15 stops `dataSync` after 6 h a day), sticky;
 * - reconnect with backoff 5 s to 2 min through an AlarmManager alarm, so it fires in Doze;
 * - reconnect at once when a network comes back;
 * - `since=<last id>` on every connect, so nothing in ntfy's 12-hour cache is missed;
 * - OkHttp pings every 3 min; the server sends a keepalive every 45 s;
 * - restarted by [PushWorker] (every 15 min, which also polls), on boot and after an update.
 */
class PushService : Service() {

    private lateinit var store: PushStore
    private lateinit var inbox: Inbox
    private lateinit var notifier: PushNotifier
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(3, TimeUnit.MINUTES)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var generation = 0
    private var errors = 0
    private var stopped = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val reconnect = AlarmManager.OnAlarmListener { connect() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.get(this)
        store = graph.pushStore
        inbox = graph.pushInbox
        notifier = graph.pushNotifier
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !store.enabled || !store.isSetUp) {
            stopped = true
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, PushNotifier.ID_LISTEN, notifier.listening(describe(state.value)),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // Started from the background without an exemption: the worker tries again.
            Log.w(TAG, "cannot start in the foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT) {
            // The address changed: drop the old connection, open the new one.
            synchronized(this) {
                generation++
                ws?.close(1000, null)
                ws = null
            }
        }
        watchNetwork()
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        cancelReconnect()
        netCallback?.let {
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        synchronized(this) {
            generation++
            ws?.close(1000, null)
            ws = null
        }
        set(State.OFF)
        // Killed while still wanted: ask the worker to bring it back.
        if (store.enabled) PushWorker.kick(this)
        super.onDestroy()
    }

    @Synchronized
    private fun connect() {
        if (stopped || ws != null) return
        cancelReconnect()
        val endpoint = store.endpoint ?: return
        val since = store.lastId?.let { "?since=$it" } ?: ""
        val url = endpoint.replaceFirst("https://", "wss://") + "/ws" + since
        val gen = ++generation
        set(State.CONNECTING)
        ws = client.newWebSocket(Request.Builder().url(url).build(), Listener(gen))
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = mine {
            errors = 0
            set(State.CONNECTED)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            val result = inbox.handle(text)
            if (!result.startsWith("ignored")) Log.i(TAG, "message: $result")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = mine { lost(null) }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = mine { lost(t) }

        private fun mine(fn: () -> Unit) = synchronized(this@PushService) { if (gen == generation) fn() }
    }

    private fun lost(t: Throwable?) {
        ws = null
        if (stopped) return
        errors++
        val wait = RETRY_SECONDS[minOf(errors, RETRY_SECONDS.size) - 1]
        Log.w(TAG, "connection lost (${t?.message ?: "closed"}), retrying in ${wait}s")
        set(State.WAITING)
        scheduleReconnect(wait)
    }

    private fun scheduleReconnect(seconds: Int) {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + seconds * 1000L
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setExact(AlarmManager.RTC_WAKEUP, at, ALARM_TAG, reconnect, main)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, ALARM_TAG, reconnect, main)
        }
    }

    private fun cancelReconnect() {
        (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(reconnect)
    }

    private fun watchNetwork() {
        if (netCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    synchronized(this@PushService) { if (ws == null) errors = 0 }
                    connect()
                }
            }
        }
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "no network callback: ${e.message}")
        }
    }

    private fun set(s: State) {
        _state.value = s
        if (s != State.OFF) notifier.updateListening(describe(s))
    }

    enum class State { OFF, CONNECTING, CONNECTED, WAITING }

    companion object {
        private const val TAG = "termulaa.push"
        private const val ACTION_STOP = "com.debkosh.termulaa.push.STOP"
        private const val ACTION_RECONNECT = "com.debkosh.termulaa.push.RECONNECT"
        private const val ALARM_TAG = "termulaa:push-reconnect"
        private val RETRY_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 120)

        private val _state = MutableStateFlow(State.OFF)
        val state: StateFlow<State> = _state

        fun describe(s: State) = when (s) {
            State.OFF -> "Off"
            State.CONNECTING -> "Connecting…"
            State.CONNECTED -> "Connected"
            State.WAITING -> "Connection lost, retrying"
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PushService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "cannot start: ${e.message}")
            }
        }

        /** Connects again, to the address now in the store. */
        fun reconnect(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, PushService::class.java).setAction(ACTION_RECONNECT),
                )
            } catch (e: Exception) {
                Log.w(TAG, "cannot reconnect: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, PushService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                context.stopService(Intent(context, PushService::class.java))
            }
        }
    }
}
