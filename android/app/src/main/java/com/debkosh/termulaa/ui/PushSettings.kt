package com.debkosh.termulaa.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.debkosh.termulaa.AppGraph
import com.debkosh.termulaa.push.PushCrypto
import com.debkosh.termulaa.push.PushMessage
import com.debkosh.termulaa.push.PushService
import com.debkosh.termulaa.push.PushWorker
import com.debkosh.termulaa.push.Speaker
import com.debkosh.termulaa.push.Wire
import io.github.sudiptadeb.amarit.Amarit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Settings section for notifications from `termulaa notify` (docs/notify.md). */
@Composable
fun PushSettings() {
    // Bumped on resume and after each change, so the store and the system
    // settings (battery, alarms, permission) are read again.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    key(tick) {
        PushSettingsBody(refresh = { tick++ }, askNotifications = {
            if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        })
    }
}

@Composable
private fun PushSettingsBody(refresh: () -> Unit, askNotifications: () -> Unit) {
    val context = LocalContext.current
    val store = AppGraph.get(context).pushStore
    val scope = rememberCoroutineScope()
    val state by PushService.state.collectAsState()
    var testResult by remember { mutableStateOf<String?>(null) }

    SectionLabel("Session notifications")

    if (!store.isSetUp || !store.enabled) {
        Text(
            "Get a notification when a terminal session finishes, needs your decision or is " +
                "blocked. It is spoken too when earphones are in. Messages are encrypted to a key " +
                "only this phone holds.",
            color = Palette.Dim, style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = {
            store.setUp()
            store.enabled = true
            askNotifications()
            PushWorker.schedule(context)
            PushService.start(context)
            refresh()
        }) { Text("Turn on", color = Palette.Green) }
        return
    }

    ToggleRow("Listening", PushService.describe(state), true) { on ->
        store.enabled = on
        if (!on) {
            PushService.stop(context)
            PushWorker.cancel(context)
        }
        refresh()
    }

    val command = "termulaa notify setup \"${store.setupLine}\""
    Text("On each computer, run once:", color = Palette.Text, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp))
    Text(
        command, color = Palette.Text, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .border(1.dp, Palette.Border, RoundedCornerShape(6.dp)).padding(10.dp),
    )
    Row {
        TextButton(onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("termulaa notify setup", command))
            testResult = "Copied."
        }) { Text("Copy", color = Palette.Green) }
        TextButton(onClick = {
            testResult = "Sending…"
            scope.launch {
                testResult = sendTest(store.endpoint!!, store.publicKey!!)
            }
        }) { Text("Send a test", color = Palette.Green) }
    }
    testResult?.let { Text(it, color = Palette.Dim, style = MaterialTheme.typography.bodySmall) }

    ToggleRow("Speak decisions", "Only into earphones, never in Do Not Disturb", store.speakDecision) {
        store.speakDecision = it; refresh()
    }
    ToggleRow("Speak blockers", "Only into earphones, never in Do Not Disturb", store.speakBlocker) {
        store.speakBlocker = it; refresh()
    }

    VoiceSettings(refresh)

    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        Text(
            "Android may pause the connection to save battery, and notifications then arrive late.",
            color = Palette.Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = {
            open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")))
        }) { Text("Let it run in the background", color = Palette.Green) }
    }
    if (Build.VERSION.SDK_INT >= 31 &&
        !(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    ) {
        TextButton(onClick = {
            open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")))
        }) { Text("Allow reconnecting on time (alarms)", color = Palette.Green) }
    }
}

/** Voice, speed, volume and music behaviour, with a sample to hear the result. */
@Composable
private fun VoiceSettings(refresh: () -> Unit) {
    val context = LocalContext.current
    val graph = AppGraph.get(context)
    val store = graph.pushStore
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    // A TextToSpeech instance only to list the voices; the Speaker makes its own per line.
    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val lang = Locale.getDefault().language
                voices = (tts?.voices ?: emptySet())
                    .filter { it.locale.language == lang && !it.features.contains("notInstalled") }
                    .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
            }
        }
        onDispose { tts?.shutdown() }
    }
    var rate by remember { mutableFloatStateOf(store.speechRate) }
    var volume by remember { mutableFloatStateOf(store.speechVolume) }

    Text("Voice", color = Palette.Text, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp))
    Box {
        TextButton(onClick = { picking = true }) {
            Text(store.voice?.let { label(it, voices) } ?: "Phone's default voice", color = Palette.Green)
        }
        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
            DropdownMenuItem(text = { Text("Phone's default voice") }, onClick = {
                store.voice = null; picking = false; refresh()
            })
            voices.forEach { v ->
                DropdownMenuItem(text = { Text(label(v.name, voices)) }, onClick = {
                    store.voice = v.name; picking = false; refresh()
                    graph.speaker.say(Speaker.SAMPLE)
                })
            }
        }
    }
    Text("Speed ${"%.1f".format(rate)}×", color = Palette.Dim, style = MaterialTheme.typography.bodySmall)
    Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.6f..1.8f, steps = 11,
        onValueChangeFinished = { store.speechRate = rate })
    Text("Volume ${(volume * 100).toInt()}% of the media volume", color = Palette.Dim,
        style = MaterialTheme.typography.bodySmall)
    Slider(value = volume, onValueChange = { volume = it }, valueRange = 0.1f..1f,
        onValueChangeFinished = { store.speechVolume = volume })
    ToggleRow("Pause music while speaking", "Off: music gets quieter under the voice", store.pauseMusic) {
        store.pauseMusic = it; refresh()
    }
    Row {
        TextButton(onClick = { graph.speaker.say(Speaker.SAMPLE) }) { Text("Play a sample", color = Palette.Green) }
        TextButton(onClick = { graph.speaker.stop() }) { Text("Stop", color = Palette.Dim) }
    }
    Text(
        "The sample plays on whatever is playing sound now. Notifications speak only into earphones.",
        color = Palette.Dim, style = MaterialTheme.typography.bodySmall,
    )
}

/** "en-in · 3" style labels: voice names are engine ids like en-in-x-ene-local. */
private fun label(name: String, voices: List<Voice>): String {
    val v = voices.firstOrNull { it.name == name } ?: return name
    val where = v.locale.displayCountry.ifBlank { v.locale.displayName }
    val net = if (v.isNetworkConnectionRequired) " · needs internet" else ""
    return "$where · ${v.name.substringAfter("-x-").substringBefore("-")}$net"
}

/** "Check for updates", through the amarit library. */
@Composable
fun UpdateButton() {
    val context = LocalContext.current
    TextButton(onClick = {
        val activity = context as? Activity ?: return@TextButton
        Amarit.current?.checkNow(activity)
    }) { Text("Check for updates", color = Palette.Green) }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, color = Palette.Dim, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private val http = OkHttpClient()

/** Encrypts a test message to this phone's own key and posts it, like `termulaa notify`. */
private suspend fun sendTest(endpoint: String, publicKey: String): String = withContext(Dispatchers.IO) {
    try {
        val m = PushMessage(level = "decision", from = "termulaa", key = "test",
            title = "Test from this phone. Notifications work.")
        val body = PushCrypto.seal(publicKey, Wire.encode(m)).toRequestBody("text/plain".toMediaType())
        http.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use {
            if (it.isSuccessful) "Sent. It should arrive in a moment." else "The server answered ${it.code}."
        }
    } catch (e: Exception) {
        "Could not send: ${e.message}"
    }
}
