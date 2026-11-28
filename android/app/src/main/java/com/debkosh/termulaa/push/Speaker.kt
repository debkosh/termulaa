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
 * for the line. Lines wait their turn: a burst of messages is said one after
 * another, never cut off by the next. [stop] silences the line and the queue.
 */
class Speaker(private val context: Context, private val store: PushStore) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var current: Line? = null
    private val waiting = ArrayDeque<Queued>()

    // Shake or face down silences the voice, like silencing a ringing phone.
    private val gesture = StopGesture(context) { stop() }

    private class Queued(val key: String?, val text: String, val spoken: () -> Unit, val asked: Boolean = false)

    /** Why it would stay quiet right now, or null when it may speak. */
    fun quietReason(): String? = when {
        !earphones() && !store.speakOnSpeaker -> "no earphones"
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

    /** Stops the line being spoken and drops every line waiting. */
    fun stop() {
        val line = synchronized(this) {
            waiting.clear()
            current
        }
        line?.finish()
    }

    /** Says [text] now, ahead of anything waiting: the settings sample. */
    fun say(text: String) {
        stop()
        synchronized(this) { waiting.addFirst(Queued(null, text, {})) }
        next()
    }

    /**
     * Says [text] after the lines before it. A line still waiting under the
     * same [key] is replaced, so an updated message is said once. [spoken]
     * runs only when the whole line was said, not when it was stopped, failed
     * or skipped because earphones went out meanwhile. An [asked] line (he
     * pressed Speak this) plays on whatever output is on, like the sample.
     */
    fun queue(text: String, key: String?, asked: Boolean = false, spoken: () -> Unit = {}) {
        synchronized(this) {
            val item = Queued(key, text, spoken, asked)
            val at = if (key == null) -1 else waiting.indexOfFirst { it.key == key }
            if (at >= 0) waiting[at] = item else waiting.addLast(item)
        }
        next()
    }

    /** Drops a waiting line, when its message is taken back. */
    fun drop(key: String) {
        synchronized(this) { waiting.removeAll { it.key == key } }
    }

    /** Starts the next waiting line if none is being said. */
    private fun next() {
        main.post {
            synchronized(this) {
                if (current != null) return@post
                while (true) {
                    val q = waiting.removeFirstOrNull()
                    if (q == null) {
                        gesture.stop()
                        return@post
                    }
                    // A notification waits its turn; the rules are checked again.
                    if (q.key != null && !q.asked && quietReason() != null) continue
                    current = Line(speakable(q.text), q.spoken)
                    if (store.gestureStop) gesture.start()
                    return@post
                }
            }
        }
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
            synchronized(this@Speaker) { if (current === this) current = null }
            next()
        }
    }

    companion object {
        /** What a sender wrote for the screen, made fit to hear: no markup, no link text. */
        fun speakable(text: String): String = text
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[`*_#>|~]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** The line read aloud for a message: who, what, and the details when [withBody]. */
        fun line(from: String, title: String, body: String?, withBody: Boolean): String {
            val details = body?.takeIf { withBody && it.isNotBlank() }?.let { speakable(it) }
            return if (details.isNullOrEmpty()) "$from: $title" else "$from: $title. $details"
        }

        const val SAMPLE = "project-b release: ready to merge 0.9, needs your yes."
    }
}
