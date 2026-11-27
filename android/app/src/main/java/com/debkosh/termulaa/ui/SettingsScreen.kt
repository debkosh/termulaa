package com.debkosh.termulaa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverUrl: String,
    versionName: String,
    pollMinutes: Int,
    offlineAlerts: Boolean,
    onBack: () -> Unit,
    onPollMinutes: (Int) -> Unit,
    onOfflineAlerts: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onNotificationSettings: () -> Unit,
    onOpenProject: () -> Unit,
) {
    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Palette.Bg, titleContentColor = Palette.Text,
                ),
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Dim)
                    }
                },
            )
        },
    ) { padding ->
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = Palette.Bg, contentColor = Palette.Green) {
                listOf("Notifications", "Terminals", "App").forEachIndexed { i, name ->
                    Tab(selected = tab == i, onClick = { tab = i },
                        text = { Text(name, color = if (tab == i) Palette.Text else Palette.Dim) })
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                when (tab) {
                    0 -> PushSettings()
                    1 -> TerminalSettings(serverUrl, pollMinutes, offlineAlerts, onPollMinutes,
                        onOfflineAlerts, onSignOut, onNotificationSettings)
                    else -> AppSettings(versionName, onOpenProject)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The memd server the terminals come from, how often machines are checked, and offline alerts. */
@Composable
private fun TerminalSettings(
    serverUrl: String,
    pollMinutes: Int,
    offlineAlerts: Boolean,
    onPollMinutes: (Int) -> Unit,
    onOfflineAlerts: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onNotificationSettings: () -> Unit,
) {
    SectionLabel("Server")
    Text(serverUrl, color = Palette.Text, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium)
    Text(
        "To change the server, sign out and connect again.",
        color = Palette.Dim, style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = onSignOut) { Text("Sign out", color = Palette.Red) }
    SettingsDivider()

    SectionLabel("Background checks")
    listOf(15, 30, 60).forEach { m ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onPollMinutes(m) },
        ) {
            RadioButton(selected = pollMinutes == m, onClick = { onPollMinutes(m) })
            Text("Every $m minutes", color = Palette.Text,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(
        "Live watch (the toggle on the Machines tab) checks every 45s.",
        color = Palette.Dim, style = MaterialTheme.typography.bodySmall,
    )
    SettingsDivider()

    SectionLabel("Machine alerts")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Offline alerts", color = Palette.Text,
                style = MaterialTheme.typography.bodyMedium)
            Text("Notify when a machine drops off", color = Palette.Dim,
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = offlineAlerts, onCheckedChange = onOfflineAlerts)
    }
    TextButton(onClick = onNotificationSettings) {
        Text("System notification settings", color = Palette.Green)
    }
}

@Composable
private fun AppSettings(versionName: String, onOpenProject: () -> Unit) {
    SectionLabel("About")
    Text("termulaa $versionName", color = Palette.Text,
        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    UpdateButton()
    TextButton(onClick = onOpenProject) {
        Text("github.com/debkosh/termulaa", color = Palette.Green,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text.uppercase(), color = Palette.Dim, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun SettingsDivider() {
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = Palette.Border)
}
