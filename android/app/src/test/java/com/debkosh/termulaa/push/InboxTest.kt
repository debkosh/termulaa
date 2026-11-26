package com.debkosh.termulaa.push

import com.debkosh.termulaa.net.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {

    // Made by `termulaa notify` (Go, filippo.io/age), so this test proves the two sides agree.
    private val goIdentity = "AGE-SECRET-KEY-1H2AER9ZS9AUR4NQLT44NZRP9RLXW6D0RKYTF3WTGT8G3Z0JAVMYSTT9RHS"
    private val goMessage = "YWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IFgyNTUxOSBuZjVudVQwZ3JPbGVwVlpZMFkyRU52dFVSVnd5NXlSV3JmYnJNZ0tBUTBRCjFHTHdicXhoT3o4aktHOGU2Q1BsSURBVHFzb1k3b05UbTlxTkxLaXFjcUkKLS0tIHBMU3pIQ2hhaGFDWEFaREl1Q053MWQ2eVZtSUxENHlkR0hGOFJxbmtISXMKQr48ffv5QXNyeu5eqYfM/XbME6p9gWMcBVhk1fuoOfiEMqIftoFX91RPXHD2pP6joxlRnLLUTqCIgrBD03mN986ZDnw/fSZ7PNGi6tx66wBB7wjI6TMAEIm5Sg05SkB0EYxFNMLsnrrvgAKSIak3ZC00T5MhL1Hizny2b1tiuptXU4qDw/0VjZ6tiCEZUsYLv+gA73/wNsz00sSr22rTaKtSp0BPRERjomhurEGNBUPoupLaplVT5XFqIWLHMbiJ0qhg6QQ8JMPMmeX2Uace7pv6tSmcOSiXIKUMyJxIrIcSQAVXv6Dh9w=="
    private val goCancel = "YWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IFgyNTUxOSBkS053NzFHSEVuVWZSL3dxdEFzd1RTZzE0RVFmamFNMm5ZYldnaXh5ZUNNCi9ndUp6dmcySzZpRUsySjJKN2ExOUxtZEp6Ujlza0piVmhydkVCM3BQTlUKLS0tIElzVVlFVHVvM1pjOExxSmlZMjdtL0hEcEsxUHJBSWRtazhYQm5sMU5xWjgK7mKeF6IcYpRFzLGKiJProdN5DG0aroAsLbN+llkdAiYRQfKagAJs6k6qdBTmRY1ROBW3j0D8f5vRQq4TW6yTt5cHc/Q="

    private fun store(identity: String = goIdentity): PushStore {
        val s = InMemorySecretStore()
        s.put("identity", identity)
        s.put("recipient", "age1unused")
        s.put("endpoint", "https://ntfy.sh/tmtest")
        return PushStore(s)
    }

    private class Rec {
        val shown = mutableListOf<PushMessage>()
        val cancelled = mutableListOf<String>()
        val spoken = mutableListOf<PushMessage>()
        val recorded = mutableListOf<HistoryEntry>()
        val silent = mutableListOf<Boolean>()
    }

    private fun inbox(store: PushStore, rec: Rec, now: Long = 1_000, canSpeak: Boolean = true) =
        Inbox(store, { m, quiet -> rec.shown += m; rec.silent += quiet }, { rec.cancelled += it },
            { rec.spoken += it }, canSpeak = { canSpeak }, record = { rec.recorded += it }, now = { now })

    private fun event(id: String, message: String, time: Long = 1_000) =
        """{"id":"$id","time":$time,"event":"message","topic":"tmtest","message":"$message"}"""

    @Test fun decryptsWhatGoSent() {
        val rec = Rec()
        val s = store()
        assertEquals("shown", inbox(s, rec).handle(event("a1", goMessage)))
        val m = rec.shown.single()
        assertEquals("decision", m.level)
        assertEquals("project-b-release", m.from)
        assertEquals("ready to merge 0.9, needs your yes", m.title)
        assertEquals("tester passed — all 42", m.body)
        assertEquals("https://claude.ai/code/session_1", m.link)
        assertEquals("project-b-release", m.key)
        assertEquals(1, rec.spoken.size)
        assertEquals("a1", s.lastId)
    }

    @Test fun shownMessagesAreKeptInFull() {
        val rec = Rec()
        inbox(store(), rec).handle(event("a1", goMessage))
        val e = rec.recorded.single()
        assertEquals("a1", e.id)
        assertEquals("decision", e.level)
        assertEquals("tester passed — all 42", e.body)
        assertEquals(1_000L, e.at)
    }

    @Test fun historyKeepsNewestFirstAndSurvivesARestart() {
        val f = java.io.File.createTempFile("history", ".json")
        val h = History(f)
        h.add(HistoryEntry("1", 1, "info", "a", "first"))
        h.add(HistoryEntry("2", 2, "blocker", "b", "second"))
        h.add(HistoryEntry("2", 2, "blocker", "b", "second"))
        assertEquals(listOf("2", "1"), History(f).entries.value.map { it.id })
        h.clear()
        assertTrue(History(f).entries.value.isEmpty())
    }

    @Test fun cancelRemovesByKey() {
        val rec = Rec()
        assertEquals("cancelled", inbox(store(), rec).handle(event("c1", goCancel)))
        assertEquals(listOf("project-b-release"), rec.cancelled)
        assertTrue(rec.shown.isEmpty())
    }

    @Test fun sameMessageTwiceShowsOnce() {
        val rec = Rec()
        val s = store()
        inbox(s, rec).handle(event("a1", goMessage))
        assertEquals("seen", inbox(s, rec).handle(event("a1", goMessage)))
        assertEquals(1, rec.shown.size)
    }

    @Test fun anotherKeyOrJunkIsDropped() {
        val rec = Rec()
        val other = PushCrypto.newKeyPair()
        assertTrue(inbox(store(other.identity), rec).handle(event("a1", goMessage)).startsWith("dropped"))
        assertTrue(inbox(store(), rec).handle(event("a2", "aGVsbG8=")).startsWith("dropped"))
        assertTrue(rec.shown.isEmpty())
    }

    @Test fun keepalivesAndOpensAreIgnored() {
        val rec = Rec()
        val i = inbox(store(), rec)
        assertEquals("ignored keepalive", i.handle("""{"id":"k","time":1,"event":"keepalive","topic":"tmtest"}"""))
        assertEquals("ignored open", i.handle("""{"id":"o","time":1,"event":"open","topic":"tmtest"}"""))
        assertEquals("not an event", i.handle("garbage"))
    }

    @Test fun oldMessagesAreShownNotSpoken() {
        val rec = Rec()
        inbox(store(), rec, now = 1_000 + Inbox.FRESH_SECONDS + 1).handle(event("a1", goMessage))
        assertEquals(1, rec.shown.size)
        assertTrue(rec.spoken.isEmpty())
    }

    @Test fun aSpokenNotificationMakesNoSoundOfItsOwn() {
        val rec = Rec()
        inbox(store(), rec).handle(event("a1", goMessage))
        assertEquals(listOf(true), rec.silent)
        val rec2 = Rec()
        inbox(store(), rec2, canSpeak = false).handle(event("a2", goMessage))
        assertEquals(listOf(false), rec2.silent)
        assertTrue(rec2.spoken.isEmpty())
    }

    @Test fun speechFollowsTheSwitch() {
        val rec = Rec()
        val s = store().apply { speakDecision = false }
        inbox(s, rec).handle(event("a1", goMessage))
        assertTrue(rec.spoken.isEmpty())
    }

    @Test fun phoneSealsWhatItOpens() {
        val keys = PushCrypto.newKeyPair()
        assertTrue(keys.recipient.startsWith("age1"))
        val plain = Wire.encode(PushMessage(level = "info", title = "hi"))
        assertEquals(plain, PushCrypto.open(keys.identity, PushCrypto.seal(keys.publicKey, plain)))
    }

    @Test fun setUpMakesALineTermulaaAccepts() {
        val s = PushStore(InMemorySecretStore())
        s.setUp()
        val (endpoint, rcpt) = s.setupLine!!.split(" ")
        assertTrue(endpoint.matches(Regex("https://ntfy\\.sh/tm[a-z0-9]{30}")))
        assertTrue(rcpt.startsWith("age1"))
        val before = s.setupLine
        s.setUp()
        assertEquals(before, s.setupLine)
    }
}
