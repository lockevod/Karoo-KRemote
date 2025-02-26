// Kotlin
package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.viewmodel.ConfigurationViewModel
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.AntRemoteKey


@Composable
fun ConfigurationScreen(
    devices: List<RemoteDevice>,
    activeDevice: RemoteDevice?,
    errorMessage: String?,
    configViewModel: ConfigurationViewModel
) {
    var selectedDeviceId by remember { mutableStateOf(activeDevice?.id) }

    val selectedDevice = devices.find { it.id == selectedDeviceId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        selectedDevice?.let { device ->
            Text(
                text = "Configuración de ${device.name}",
                style = MaterialTheme.typography.headlineSmall
            )

            val deviceOptions = devices.map { DropdownOption(it.id, it.name) }
            val selectedDeviceOption = deviceOptions.find { it.id == device.id }
                ?: deviceOptions.firstOrNull()
                ?: DropdownOption("", "No devices")

            KarooKeyDropdown(
                remotekey = "Dispositivo",
                options = deviceOptions,
                selectedOption = selectedDeviceOption,
                onSelect = { selectedOption: DropdownOption ->  // Especificamos el tipo explícitamente
                    selectedDeviceId = selectedOption.id
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            LearnedCommandsSection(
                device = device,
                onKarooKeyAssigned = { command, karooKey ->
                    configViewModel.assignKeyCodeToCommand(device.id, command, karooKey)
                }
            )
        } ?: run {
            Text(
                text = "No hay dispositivos activos",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        errorMessage?.let { error ->
            AlertDialog(
                onDismissRequest = { configViewModel.clearError() },
                title = { Text("Error") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { configViewModel.clearError() }) {
                        Text("Aceptar")
                    }
                }
            )
        }
    }
}
@Composable
fun LearnedCommandsSection(
    device: RemoteDevice,
    onKarooKeyAssigned: (AntRemoteKey, KarooKey?) -> Unit
) {
    val commands = if (device.learnedCommands.isEmpty()) {
        RemoteDevice.getDefaultLearnedCommands()
    } else {
        device.learnedCommands
    }

    Column {
        Text("Asignar KarooKey a Comandos:")
        if (commands.isEmpty()) {
            Text("No hay comandos aprendidos ni comandos por defecto disponibles.")
        } else {
            commands.forEach { learnedCommand ->
                Column {
                    Text(text = learnedCommand.command.label)

                    val options = listOf(DropdownOption("null", "None")) +
                            KarooKey.entries.map { DropdownOption(it.name, it.label) }
                    val selectedOption = options.find { it.id == learnedCommand.karooKey?.name }
                        ?: options[0]

                    KarooKeyDropdown(
                        remotekey = learnedCommand.command.label,
                        options = options,
                        selectedOption = selectedOption,
                        onSelect = { selected: DropdownOption ->
                            val karooKey = KarooKey.entries.find { it.name == selected.id }
                            onKarooKeyAssigned(learnedCommand.command, karooKey)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}