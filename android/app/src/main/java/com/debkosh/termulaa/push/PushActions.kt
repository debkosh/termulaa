package com.debkosh.termulaa.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.debkosh.termulaa.AppGraph

/**
 * The notification buttons: "Speak this" on a session's notification, and
 * the listening notification's All / Important / None, which choose what is
 * spoken.
 */
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.get(context)
        val store = graph.pushStore
        when (intent.action) {
            SPEAK -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                // Heard in full: the notification has done its job, as when spoken by itself.
                graph.speaker.queue(text, key, asked = true) { graph.pushNotifier.cancel(key) }
                return
            }
            VOICE_ALL -> store.voiceMode = PushStore.VoiceMode.ALL
            VOICE_IMPORTANT -> store.voiceMode = PushStore.VoiceMode.IMPORTANT
            VOICE_NONE -> {
                store.voiceMode = PushStore.VoiceMode.NONE
                graph.speaker.stop()
            }
            else -> return
        }
        val state = PushService.state.value
        if (state != PushService.State.OFF) graph.pushNotifier.updateListening(PushService.describe(state))
    }

    companion object {
        const val SPEAK = "com.debkosh.termulaa.push.SPEAK"
        const val EXTRA_KEY = "key"
        const val EXTRA_TEXT = "text"
        const val VOICE_ALL = "com.debkosh.termulaa.push.VOICE_ALL"
        const val VOICE_IMPORTANT = "com.debkosh.termulaa.push.VOICE_IMPORTANT"
        const val VOICE_NONE = "com.debkosh.termulaa.push.VOICE_NONE"
    }
}
