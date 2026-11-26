package com.debkosh.termulaa.push

import kage.Age
import kage.crypto.x25519.X25519
import kage.crypto.x25519.X25519Identity
import kage.crypto.x25519.X25519Recipient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * The decrypted notification. Field names are the wire contract with
 * `termulaa notify` (src/cmd/termulaa/notify.go) and docs/notify.md.
 */
@Serializable
data class PushMessage(
    val v: Int = 1,
    val level: String? = null,
    val from: String? = null,
    val title: String? = null,
    val body: String? = null,
    val link: String? = null,
    val key: String? = null,
    val cancel: String? = null,
)

/** One line of ntfy's subscribe stream (WebSocket frame or JSON poll line). */
@Serializable
data class NtfyEvent(
    val id: String = "",
    val time: Long = 0,
    val event: String = "",
    val message: String? = null,
)

object Wire {
    val json = Json { ignoreUnknownKeys = true }

    fun event(text: String): NtfyEvent? = try {
        json.decodeFromString(NtfyEvent.serializer(), text)
    } catch (_: Exception) {
        null
    }

    fun message(text: String): PushMessage =
        json.decodeFromString(PushMessage.serializer(), text)

    fun encode(m: PushMessage): String = json.encodeToString(PushMessage.serializer(), m)
}

/** age X25519, through kage: the phone holds the identity, senders the recipient. */
object PushCrypto {
    /**
     * A new key pair. [KeyPair.publicKey] is kept beside the "age1…" string
     * because kage parses recipients only internally.
     */
    fun newKeyPair(): KeyPair {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val public = X25519.scalarMultBase(secret)
        return KeyPair(
            identity = X25519Identity(secret, public).encodeToString(),
            recipient = X25519Recipient(public).encodeToString(),
            publicKey = Base64.getEncoder().encodeToString(public),
        )
    }

    class KeyPair(val identity: String, val recipient: String, val publicKey: String)

    /** base64 age ciphertext → plaintext. Throws on anything not for this key. */
    fun open(identity: String, sealed: String): String {
        val raw = Base64.getDecoder().decode(sealed.trim())
        val out = ByteArrayOutputStream()
        Age.decryptStream(listOf(X25519Identity.decode(identity)), raw.inputStream(), out)
        return out.toString(Charsets.UTF_8.name())
    }

    /** plaintext → base64 age ciphertext, as `termulaa notify` sends it. */
    fun seal(publicKey: String, plain: String): String {
        val out = ByteArrayOutputStream()
        val recipient = X25519Recipient(Base64.getDecoder().decode(publicKey))
        Age.encryptStream(listOf(recipient), plain.byteInputStream(), out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** A topic name nobody guesses: 32 characters of [a-z0-9]. */
    fun newTopic(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return "tm" + (1..30).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }
}
