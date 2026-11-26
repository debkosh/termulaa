package com.debkosh.termulaa.push

import android.content.Context
import com.debkosh.termulaa.net.EncryptedSecretStore
import com.debkosh.termulaa.net.SecretStore

/**
 * Phone-notification state, in its own encrypted file so signing out of memd
 * does not touch it: the private key, the topic, the stream cursor and the
 * speech choices.
 */
class PushStore(private val s: SecretStore) {

    constructor(context: Context) : this(EncryptedSecretStore(context, FILE))

    val identity: String? get() = s.get(K_IDENTITY)
    val recipient: String? get() = s.get(K_RECIPIENT)
    /** The same key as [recipient], base64, for the phone's own test message. */
    val publicKey: String? get() = s.get(K_PUBLIC)
    /** https://ntfy.sh/<topic> */
    val endpoint: String? get() = s.get(K_ENDPOINT)

    val isSetUp: Boolean get() = identity != null && recipient != null && endpoint != null

    /** What `termulaa notify setup` takes: "<endpoint> <age1…>". */
    val setupLine: String? get() = if (isSetUp) "$endpoint $recipient" else null

    var enabled: Boolean
        get() = s.get(K_ENABLED) == "1"
        set(v) = s.put(K_ENABLED, if (v) "1" else null)

    var speakDecision: Boolean
        get() = s.get(K_SPEAK_DECISION) != "0"
        set(v) = s.put(K_SPEAK_DECISION, if (v) null else "0")

    var speakBlocker: Boolean
        get() = s.get(K_SPEAK_BLOCKER) != "0"
        set(v) = s.put(K_SPEAK_BLOCKER, if (v) null else "0")

    /** Finished updates are only shown unless this is on. */
    var speakInfo: Boolean
        get() = s.get(K_SPEAK_INFO) == "1"
        set(v) = s.put(K_SPEAK_INFO, if (v) "1" else null)

    /** The text-to-speech voice's name; null is the engine's default. */
    var voice: String?
        get() = s.get(K_VOICE)
        set(v) = s.put(K_VOICE, v)

    var speechRate: Float
        get() = s.get(K_RATE)?.toFloatOrNull() ?: 1f
        set(v) = s.put(K_RATE, v.toString())

    /** 0..1, on top of the media volume. */
    var speechVolume: Float
        get() = s.get(K_VOLUME)?.toFloatOrNull() ?: 1f
        set(v) = s.put(K_VOLUME, v.toString())

    /** Pause music for the line instead of lowering it. */
    var pauseMusic: Boolean
        get() = s.get(K_PAUSE) == "1"
        set(v) = s.put(K_PAUSE, if (v) "1" else null)

    /** Epoch millis until which nothing is spoken (the "Mute voice" button). */
    var mutedUntil: Long
        get() = s.get(K_MUTED)?.toLongOrNull() ?: 0L
        set(v) = s.put(K_MUTED, if (v > 0) v.toString() else null)

    /** The id of the last ntfy message handled: the `since=` of the next connect. */
    var lastId: String?
        get() = s.get(K_LAST_ID)
        set(v) = s.put(K_LAST_ID, v)

    /** Makes the key pair and topic once. */
    @Synchronized
    fun setUp(server: String = DEFAULT_SERVER) {
        if (isSetUp) return
        val keys = PushCrypto.newKeyPair()
        s.put(K_IDENTITY, keys.identity)
        s.put(K_RECIPIENT, keys.recipient)
        s.put(K_PUBLIC, keys.publicKey)
        s.put(K_ENDPOINT, server.trimEnd('/') + "/" + PushCrypto.newTopic())
    }

    /**
     * True the first time an id is seen. The stream and the poll worker can
     * both deliver one message; the last [RECENT] ids decide.
     */
    @Synchronized
    fun firstSight(id: String): Boolean {
        val recent = s.get(K_RECENT)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        if (id in recent) return false
        s.put(K_RECENT, (recent + id).takeLast(RECENT).joinToString(","))
        return true
    }

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        private const val FILE = "termulaa_push"
        private const val RECENT = 100
        private const val K_IDENTITY = "identity"
        private const val K_RECIPIENT = "recipient"
        private const val K_PUBLIC = "public_key"
        private const val K_ENDPOINT = "endpoint"
        private const val K_ENABLED = "enabled"
        private const val K_SPEAK_DECISION = "speak_decision"
        private const val K_SPEAK_BLOCKER = "speak_blocker"
        private const val K_LAST_ID = "last_id"
        private const val K_RECENT = "recent_ids"
        private const val K_VOICE = "voice"
        private const val K_SPEAK_INFO = "speak_info"
        private const val K_RATE = "speech_rate"
        private const val K_VOLUME = "speech_volume"
        private const val K_PAUSE = "pause_music"
        private const val K_MUTED = "muted_until"
    }
}
