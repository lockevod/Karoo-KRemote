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
import androidx.compose.ui.res.stringResource
import com.enderthor.kremote.R


@Composable
fun ConfigurationScreen(
    devices: List<RemoteDevice>,
    activeDevice: RemoteDevice?,
    errorMessage: String?,
    configViewModel: ConfigurationViewModel
) {
    var selectedDeviceId by remember { mutableStateOf(activeDevice?.id) }
    val selectedDevice = devices.find { it.id == selectedDeviceId }
    val onlyWhileRiding by configViewModel.onlyWhileRiding.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        selectedDevice?.let { device ->
            val deviceOptions = devices.map { DropdownOption(it.id, it.name) }
            val selectedDeviceOption = deviceOptions.find { it.id == device.id }
                ?: deviceOptions.firstOrNull()
                ?: DropdownOption("", "No devices")

            KarooKeyDropdown(
                remotekey = "Device",
                options = deviceOptions,
                selectedOption = selectedDeviceOption,
                onSelect = { selectedOption: DropdownOption ->
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

            // Añadir espaciador antes de la configuración global
            Spacer(modifier = Modifier.height(24.dp))

            // Sección de configuración global al final
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                   Text(
                        text = stringResource(R.string.global_settings),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = onlyWhileRiding,
                            onCheckedChange = { checked ->
                                configViewModel.updateOnlyWhileRiding(checked)
                            }
                        )

                        Text(
                            text = stringResource(R.string.only_while_riding),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

        } ?: run {
            Text(
                text = stringResource(R.string.no_active_devices),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        errorMessage?.let { error ->
            AlertDialog(
                onDismissRequest = { configViewModel.clearError() },
                title = { Text(stringResource(R.string.error)) },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { configViewModel.clearError() }) {
                        Text(stringResource(R.string.ok))
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
    val commands = remember(device.learnedCommands) {
        if (device.learnedCommands.isEmpty()) {
            RemoteDevice.getDefaultLearnedCommands()
        } else {
            // Asegurarse de que todos los comandos por defecto estén presentes
            val defaultCommands = RemoteDevice.getDefaultLearnedCommands()
            val existingCommandTypes = device.learnedCommands.map { it.command }

            device.learnedCommands + defaultCommands.filter {
                it.command !in existingCommandTypes
            }
        }
    }

    Column {
        Text(stringResource(R.string.assign_karookey_to_commands))
        commands.forEach { learnedCommand ->
            Column {

                Spacer(modifier = Modifier.height(4.dp))

                val options = remember {
                    listOf(DropdownOption("null", "None")) +
                    KarooKey.entries.map { DropdownOption(it.name, it.label) }
                }

                val selectedOption = options.find {
                    it.id == learnedCommand.karooKey?.name
                } ?: options[0]

                KarooKeyDropdown(
                    remotekey = learnedCommand.command.label,
                    options = options,
                    selectedOption = selectedOption,
                    onSelect = { selected ->
                        val karooKey = if (selected.id == "null") {
                            null
                        } else {
                            KarooKey.entries.find { it.name == selected.id }
                        }
                        onKarooKeyAssigned(learnedCommand.command, karooKey)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}