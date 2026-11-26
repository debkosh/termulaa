package com.debkosh.termulaa.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.debkosh.termulaa.AppGraph

/** The notification buttons: stop the line being spoken, mute or unmute the voice. */
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.get(context)
        when (intent.action) {
            STOP -> graph.speaker.stop()
            MUTE_HOUR -> {
                graph.speaker.stop()
                graph.pushStore.mutedUntil = System.currentTimeMillis() + 60 * 60 * 1000L
            }
            UNMUTE -> graph.pushStore.mutedUntil = 0L
            else -> return
        }
        val state = PushService.state.value
        if (state != PushService.State.OFF) graph.pushNotifier.updateListening(PushService.describe(state))
    }

    companion object {
        const val STOP = "com.debkosh.termulaa.push.STOP_VOICE"
        const val MUTE_HOUR = "com.debkosh.termulaa.push.MUTE_HOUR"
        const val UNMUTE = "com.debkosh.termulaa.push.UNMUTE"
    }
}
