package com.debkosh.termulaa.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.debkosh.termulaa.AppGraph
import com.debkosh.termulaa.push.HistoryEntry
import com.debkosh.termulaa.push.PushMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check: renders the notification screens with sample messages and
 * saves a screenshot of each to /sdcard/Download/tour-*.png, for a
 * person to look at. Run on demand with -e class ...ScreensTour.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTour {
    @get:Rule val rule = createComposeRule()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val graph = AppGraph.get(context)

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(600)
        capture(name)
    }

    // The shell may write to /sdcard/Download, which outlives the test run.
    private fun capture(name: String) {
        val pfd = inst.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/tour-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun seed() {
        val now = System.currentTimeMillis() / 1000
        val h = graph.pushHistory
        h.clear()
        fun add(i: Int, ago: Long, level: String, from: String, title: String, body: String = "", key: String = from) =
            h.add(HistoryEntry(id = "t$i", at = now - ago, level = level, from = from, title = title, body = body,
                link = "https://claude.ai/code/session_x$i", key = key))
        add(1, 7200, "info", "[project-b] release", "The nightly build passed on every platform.")
        add(2, 3000, "info", "[memd] phone notifications", "Notification channels are live in memd.")
        add(3, 2400, "decision", "[project-b] release", "Ready to merge 0.9; it needs your yes.",
            "Changelog and screenshots are in the merge request.")
        add(4, 900, "blocker", "[project-b] passwords", "The keychain test needs you to unlock the Mac.")
        add(5, 300, "info", "[setup] home interior discussion", "Drafted three layouts for the study.")
        add(6, 60, "decision", "[project-b] ux: homepage", "Pick a hero image: the dark one or the light one?")
        h.markRead("[memd] phone notifications")
    }

    @Test fun tour() {
        seed()
        rule.setContent {
            TermulaaTheme {
                MachinesScreen(MachinesUiState(), notifications = { NotificationsList(System.currentTimeMillis()) })
            }
        }
        shot("1-conversations")
        rule.onNodeWithText("release").performClick()
        shot("2-thread")
    }

    @Test fun settings() {
        graph.pushStore.setUp()
        graph.pushStore.enabled = true
        rule.setContent {
            TermulaaTheme {
                SettingsScreen("https://memd.example.com", "0.8.0", 15, true, {}, {}, {}, {}, {}, {})
            }
        }
        shot("3-settings-notifications")
        rule.onNodeWithText("Terminals").performClick()
        shot("4-settings-terminals")
    }

    @Test fun popup() {
        inst.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close()
        Thread.sleep(500)
        graph.popup.show(PushMessage(level = "decision", from = "[project-b] release",
            title = "Ready to merge 0.9; it needs your yes.", body = "Changelog and screenshots are in the merge request."))
        graph.popup.show(PushMessage(level = "info", from = "[memd] phone notifications",
            title = "Notification channels are live in memd."), spoken = true)
        Thread.sleep(1500)
        capture("5-popup")
    }
}
