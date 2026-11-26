package com.debkosh.termulaa.push

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Says a notification out loud, only into earphones, only with Do Not
 * Disturb off and no call going. Music is lowered (or paused, by choice)
 * for the line. One line at a time; [stop] cuts it off.
 */
class Speaker(private val context: Context, private val store: PushStore) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Volatile private var current: Line? = null

    /** Why it would stay quiet right now, or null when it may speak. */
    fun quietReason(): String? = when {
        System.currentTimeMillis() < store.mutedUntil -> "voice muted"
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

    /** Stops the line being spoken, if any. */
    fun stop() {
        current?.finish()
    }

    /**
     * Speaks [text] with the chosen voice, speed and volume (the sample ignores
     * the quiet rules). [spoken] runs only when the whole line was said, not
     * when it was stopped or failed.
     */
    fun say(text: String, spoken: () -> Unit = {}) {
        stop()
        current = Line(speakable(text), spoken)
    }

    private inner class Line(text: String, private val spoken: () -> Unit) {
        private val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        private val focus = AudioFocusRequest.Builder(
            if (store.pauseMusic) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        ).setAudioAttributes(attrs).build()
        // The service may be the only thing awake; hold the CPU for the line.
        private val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termulaa:speak")
            .apply { acquire(60_000) }
        private var finished = false
        private var tts: TextToSpeech? = null

        init {
            tts = TextToSpeech(context.applicationContext) { status ->
                val t = tts
                if (status != TextToSpeech.SUCCESS || t == null) {
                    finish()
                    return@TextToSpeech
                }
                store.voice?.let { name -> t.voices?.firstOrNull { it.name == name }?.let { t.voice = it } }
                t.setSpeechRate(store.speechRate)
                t.setAudioAttributes(attrs)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        val whole = !finished
                        finish()
                        if (whole) spoken()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = finish()
                    override fun onStop(id: String?, interrupted: Boolean) = finish()
                })
                audio.requestAudioFocus(focus)
                val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, store.speechVolume) }
                t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "push")
            }
        }

        @Synchronized
        fun finish() {
            if (finished) return
            finished = true
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Exception) {
            }
            audio.abandonAudioFocusRequest(focus)
            if (wake.isHeld) wake.release()
            if (current === this) current = null
        }
    }

    companion object {
        /** What a sender wrote for the screen, made fit to hear: no markup, no link text. */
        fun speakable(text: String): String = text
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[`*_#>|~]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        const val SAMPLE = "project-b release: ready to merge 0.9, needs your yes."
    }
}
