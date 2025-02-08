package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enderthor.kremote.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Configuración de conexión",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Conexión automática al inicio
        SettingsSwitchItem(
            title = "Conectar automáticamente al inicio",
            checked = settings.autoConnectOnStart,
            onCheckedChange = { viewModel.updateAutoConnectOnStart(it) }
        )

        // Reconexión automática
        SettingsSwitchItem(
            title = "Reconectar automáticamente",
            checked = settings.autoReconnect,
            onCheckedChange = { viewModel.updateAutoReconnect(it) }
        )

        if (settings.autoReconnect) {
            Spacer(modifier = Modifier.height(16.dp))

            // Intentos de reconexión
            Text("Intentos de reconexión")
            Slider(
                value = settings.reconnectAttempts.toFloat(),
                onValueChange = { viewModel.updateReconnectAttempts(it.toInt()) },
                valueRange = 1f..5f,
                steps = 4
            )
            Text("${settings.reconnectAttempts} intentos")

            Spacer(modifier = Modifier.height(16.dp))

            // Delay entre intentos
            Text("Tiempo entre intentos")
            Slider(
                value = settings.reconnectDelayMs / 1000f,
                onValueChange = { viewModel.updateReconnectDelay(it.toLong() * 1000) },
                valueRange = 1f..30f
            )
            Text("${settings.reconnectDelayMs / 1000} segundos")
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