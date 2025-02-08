package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.viewmodel.ConfigurationViewModel

@Composable
fun ConfigurationScreen(
    viewModel: ConfigurationViewModel = viewModel()
) {
    val activeDevice by viewModel.activeDevice.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        activeDevice?.let { device ->
            Text(
                text = "Configuración de ${device.name}",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            KeyMappingSection(
                title = "Botón Izquierdo",
                currentKey = device.keyMappings.remoteleft,
                onKeySelected = { viewModel.updateKeyMapping(device.id, "left", it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            KeyMappingSection(
                title = "Botón Derecho",
                currentKey = device.keyMappings.remoteright,
                onKeySelected = { viewModel.updateKeyMapping(device.id, "right", it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            KeyMappingSection(
                title = "Botón Superior",
                currentKey = device.keyMappings.remoteup,
                onKeySelected = { viewModel.updateKeyMapping(device.id, "up", it) }
            )
        } ?: run {
            Text(
                text = "No hay dispositivo activo",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        errorMessage?.let { error ->
            AlertDialog(
                onDismissRequest = { viewModel.clearError() },
                title = { Text("Error") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Aceptar")
                    }
                }
            )
        }
    }
}

@Composable
fun KeyMappingSection(
    title: String,
    currentKey: KarooKey,
    onKeySelected: (KarooKey) -> Unit
) {
    Column {
        Text(text = title)
        val options = KarooKey.entries.map {
            DropdownOption(it.name, it.label)
        }
        val selectedOption = DropdownOption(currentKey.name, currentKey.label)

        KarooKeyDropdown(
            remotekey = title,
            options = options,
            selectedOption = selectedOption,
            onSelect = { option ->
                onKeySelected(KarooKey.valueOf(option.id))
            }
        )
    }
}