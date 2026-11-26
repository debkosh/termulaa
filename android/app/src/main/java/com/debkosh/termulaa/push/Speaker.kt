package com.debkosh.termulaa.push

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Says a notification out loud, only into earphones, only with Do Not
 * Disturb off and no call going. Music ducks for the line.
 */
class Speaker(private val context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Why it would stay quiet right now, or null when it may speak. */
    fun quietReason(): String? = when {
        !earphones() -> "no earphones"
        nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL -> "Do Not Disturb"
        audio.mode != AudioManager.MODE_NORMAL -> "a call"
        else -> null
    }

    private fun earphones(): Boolean {
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= 28) types += AudioDeviceInfo.TYPE_HEARING_AID
        if (Build.VERSION.SDK_INT >= 31) types += AudioDeviceInfo.TYPE_BLE_HEADSET
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    fun say(text: String) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        // The service may be the only thing awake; hold the CPU for the line.
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termulaa:speak")
            .apply { acquire(30_000) }
        var tts: TextToSpeech? = null
        val done = {
            audio.abandonAudioFocusRequest(focus)
            tts?.shutdown()
            if (wake.isHeld) wake.release()
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts
            if (status != TextToSpeech.SUCCESS || t == null) {
                done()
                return@TextToSpeech
            }
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done()
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = done()
            })
            audio.requestAudioFocus(focus)
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "push")
        }
    }
}
