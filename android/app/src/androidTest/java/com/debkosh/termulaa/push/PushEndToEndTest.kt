package com.debkosh.termulaa.push

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.debkosh.termulaa.AppGraph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole path on a real device: the listening service connects to ntfy.sh,
 * a sealed message posted to the topic shows as a notification, and a cancel
 * removes it. Needs internet. Leaves notifications set up and listening, and
 * logs the setup line (tag termulaa.e2e) so `termulaa notify` can be tried next.
 */
@RunWith(AndroidJUnit4::class)
class PushEndToEndTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val http = OkHttpClient()

    private fun waitFor(what: String, seconds: Int = 30, ok: () -> Boolean) {
        repeat(seconds * 4) {
            if (ok()) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun post(endpoint: String, publicKey: String, m: PushMessage) {
        val body = PushCrypto.seal(publicKey, Wire.encode(m)).toRequestBody("text/plain".toMediaType())
        http.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use {
            assertTrue("ntfy answered ${it.code}", it.isSuccessful)
        }
    }

    private fun shown(key: String) = nm.activeNotifications.firstOrNull { it.tag == "push:$key" }

    @Test fun messageArrivesAndCancelRemovesIt() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        val store = AppGraph.get(context).pushStore
        store.setUp()
        store.enabled = true
        Log.i("termulaa.e2e", "setup line: ${store.setupLine}")

        PushService.start(context)
        waitFor("the connection") { PushService.state.value == PushService.State.CONNECTED }

        val key = "e2e-${System.currentTimeMillis()}"
        post(store.endpoint!!, store.publicKey!!,
            PushMessage(level = "decision", from = "e2e", key = key, title = "end to end works"))
        waitFor("the notification") { shown(key) != null }
        val n = shown(key)!!
        assertEquals(PushNotifier.CHANNEL_DECISION, n.notification.channelId)
        assertEquals("e2e", n.notification.extras.getString("android.title"))

        post(store.endpoint!!, store.publicKey!!, PushMessage(cancel = key))
        waitFor("the cancel") { shown(key) == null }
    }

    /**
     * Opt in with -Pandroid.testInstrumentationRunnerArguments.fromComputer=1: logs the
     * setup line, then waits two minutes for `termulaa notify info "…" -key computer`
     * run on a computer with that line.
     */
    @Test fun messageFromTermulaaNotify() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("fromComputer") == "1")
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        val store = AppGraph.get(context).pushStore
        store.setUp()
        store.enabled = true
        PushService.start(context)
        waitFor("the connection") { PushService.state.value == PushService.State.CONNECTED }
        Log.i("termulaa.e2e", "READY ${store.setupLine}")
        waitFor("termulaa notify", seconds = 120) { shown("computer") != null }
        Log.i("termulaa.e2e", "GOT ${shown("computer")!!.notification.extras.getCharSequence("android.text")}")
    }
}
