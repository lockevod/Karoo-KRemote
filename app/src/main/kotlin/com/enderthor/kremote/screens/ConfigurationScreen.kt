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
import com.enderthor.kremote.data.LearnedCommand
import com.enderthor.kremote.data.PressType


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
                onKarooKeyAssigned = { command, karooKey, pressType ->
                    configViewModel.assignKeyCodeToCommand(device.id, command, karooKey, pressType)
                }
            )

            // Añadir sección de configuración de doble pulsación
            Spacer(modifier = Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.double_tap_configuration),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = device.enabledDoubleTap,
                            onCheckedChange = { checked ->
                                configViewModel.updateDoubleTapEnabled(device.id, checked)
                            }
                        )

                        Text(
                            text = stringResource(R.string.enable_double_tap),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    // Mostrar el control deslizante solo si la doble pulsación está habilitada
                    if (device.enabledDoubleTap) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.double_tap_timeout, device.doubleTapTimeout.toInt()),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Slider(
                            value = device.doubleTapTimeout.toFloat(),
                            onValueChange = { value ->
                                configViewModel.updateDoubleTapTimeout(device.id, value.toLong())
                            },
                            valueRange = 200f..1000f,
                            steps = 8, // Pasos de 100ms
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = stringResource(R.string.lower_values_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

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
    onKarooKeyAssigned: (AntRemoteKey, KarooKey?, PressType) -> Unit
) {
    val defaultCommands = remember { AntRemoteKey.entries }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.assign_karookey_to_commands),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            defaultCommands.forEach { command ->
                // Comandos existentes o nuevos
                val singleCommand = device.learnedCommands.find {
                    it.command == command && it.pressType == PressType.SINGLE
                } ?: LearnedCommand(command, PressType.SINGLE)

                val doubleCommand = if (device.enabledDoubleTap) {
                    device.learnedCommands.find {
                        it.command == command && it.pressType == PressType.DOUBLE
                    } ?: LearnedCommand(command, PressType.DOUBLE)
                } else null

                CommandAssignmentRow(
                    command = command,
                    singleCommand = singleCommand,
                    doubleCommand = doubleCommand,
                    onKarooKeyAssigned = onKarooKeyAssigned
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
fun CommandAssignmentRow(
    command: AntRemoteKey,
    singleCommand: LearnedCommand,
    doubleCommand: LearnedCommand?,
    onKarooKeyAssigned: (AntRemoteKey, KarooKey?, PressType) -> Unit
) {
    val noneString = stringResource(R.string.none)
    val options = remember {
        listOf(DropdownOption("null", noneString)) +
                KarooKey.entries.map { DropdownOption(it.name, it.label) }
    }

    Column {
        Text(
            text = command.label,
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Pulsación simple
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.single_press),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(120.dp)
            )

            val selectedOption = options.find {
                it.id == singleCommand.karooKey?.name
            } ?: options[0]

            KarooKeyDropdown(
                remotekey = "",
                options = options,
                selectedOption = selectedOption,
                onSelect = { selected ->
                    val karooKey = if (selected.id == "null") null
                                  else KarooKey.entries.find { it.name == selected.id }
                    onKarooKeyAssigned(command, karooKey, PressType.SINGLE)
                }
            )
        }

        // Doble pulsación (solo si está habilitada)
        doubleCommand?.let {
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.double_press),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(120.dp)
                )

                val selectedOption = options.find {
                    it.id == doubleCommand.karooKey?.name
                } ?: options[0]

                KarooKeyDropdown(
                    remotekey = "",
                    options = options,
                    selectedOption = selectedOption,
                    onSelect = { selected ->
                        val karooKey = if (selected.id == "null") null
                                      else KarooKey.entries.find { it.name == selected.id }
                        onKarooKeyAssigned(command, karooKey, PressType.DOUBLE)
                    }
                )
            }
        }
    }
}




