package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.enderthor.kremote.data.GlobalSettings
import com.enderthor.kremote.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    settings: GlobalSettings,
    settingsViewModel: SettingsViewModel
) {


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Connection settings",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Conexión automática al inicio
        SettingsSwitchItem(
            title = "On Start connection",
            checked = settings.autoConnectOnStart,
            onCheckedChange = { settingsViewModel.updateAutoConnectOnStart(it) }
        )

        // Reconexión automática
        SettingsSwitchItem(
            title = "Autoreconnect",
            checked = settings.autoReconnect,
            onCheckedChange = { settingsViewModel.updateAutoReconnect(it) }
        )

        if (settings.autoReconnect) {
            Spacer(modifier = Modifier.height(16.dp))

            // Intentos de reconexión
            Text("Reconnection attempts")
            Slider(
                value = settings.reconnectAttempts.toFloat(),
                onValueChange = { settingsViewModel.updateReconnectAttempts(it.toInt()) },
                valueRange = 1f..5f,
                steps = 4
            )
            Text("${settings.reconnectAttempts} attempts")

            Spacer(modifier = Modifier.height(16.dp))

            // Delay entre intentos
            Text("Delay between attempts")
            Slider(
                value = settings.reconnectDelayMs / 1000f,
                onValueChange = { settingsViewModel.updateReconnectDelay(it.toLong() * 1000) },
                valueRange = 1f..30f
            )
            Text("${settings.reconnectDelayMs / 1000} seconds")
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}