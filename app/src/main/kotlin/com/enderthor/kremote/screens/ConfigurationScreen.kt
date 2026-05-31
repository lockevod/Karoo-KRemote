// Kotlin
package com.enderthor.kremote.screens


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign

import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.viewmodel.ConfigurationViewModel
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.R
import com.enderthor.kremote.data.LearnedCommand
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.getLabelString


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
    val forcedScreenOn by configViewModel.forcedScreenOn.collectAsState()
    val bypassMute by configViewModel.bypassMute.collectAsState()
    val buzzerTestResult by configViewModel.buzzerTestResult.collectAsState()
    val buzzerTesting by configViewModel.buzzerTesting.collectAsState()
    var showDoubleTapDisclaimer by remember { mutableStateOf(false) }
    var tempDeviceId by remember { mutableStateOf("") }

    val context = LocalContext.current
    val repository = remember { RemoteRepository(context) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
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

                Spacer(modifier = Modifier.height(14.dp))


                LearnedCommandsSection(
                    device = device,
                    repository = repository,
                    onKarooKeyAssigned = { command, karooKey, pressType ->
                        configViewModel.assignKeyCodeToCommand(
                            device.id,
                            command,
                            karooKey,
                            pressType
                        )
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.double_tap_configuration),
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = device.enabledDoubleTap,
                                onCheckedChange = { newValue ->
                                    if (newValue) {
                                        tempDeviceId = device.id
                                        showDoubleTapDisclaimer = true
                                    } else {

                                        configViewModel.updateDoubleTapEnabled(device.id, false)
                                    }
                                }
                            )

                            Text(
                                text = stringResource(R.string.enable_double_tap),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }


                        if (device.enabledDoubleTap) {
                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = stringResource(
                                    R.string.double_tap_timeout,
                                    device.doubleTapTimeout.toInt()
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Slider(
                                value = device.doubleTapTimeout.toFloat(),
                                onValueChange = { value ->
                                    configViewModel.updateDoubleTapTimeout(device.id, value.toLong())
                                },
                                valueRange = 1000f..2200f,
                                // 11 intermediate stops + 2 endpoints = 13 stops spaced at exactly 100ms.
                                // (2200-1000) / (11+1) = 100. Antes era 24 con redondeo manual, lo que
                                // hacía que 3 ticks consecutivos del slider guardaran el mismo valor.
                                steps = 11,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = stringResource(R.string.lower_values_explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = stringResource(
                                    R.string.double_tap_delay_warning,
                                    device.doubleTapTimeout.toInt()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = forcedScreenOn,
                                onCheckedChange = { checked ->
                                    configViewModel.updateForcedScreenOn(checked)
                                }
                            )

                            Text(
                                text = stringResource(R.string.forced_screen_on),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = bypassMute,
                                onCheckedChange = { checked ->
                                    configViewModel.updateBypassMute(checked)
                                }
                            )

                            Text(
                                text = stringResource(R.string.bypass_mute),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        Text(
                            text = stringResource(R.string.bypass_mute_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { configViewModel.testBuzzer() },
                                enabled = !buzzerTesting
                            ) {
                                if (buzzerTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text(stringResource(R.string.bypass_mute_test))
                                }
                            }

                            buzzerTestResult?.let { result ->
                                val ok = result == "SUCCESS"
                                Text(
                                    text = result,
                                    modifier = Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ok) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(22.dp))
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
            if (showDoubleTapDisclaimer) {
                AlertDialog(
                    onDismissRequest = { showDoubleTapDisclaimer = false },
                    title = { Text(stringResource(R.string.double_tap_disclaimer_title)) },
                    text = {
                        Box(
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = stringResource(R.string.double_tap_disclaimer_message),
                                textAlign = TextAlign.Justify,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            configViewModel.updateDoubleTapEnabled(tempDeviceId, true)
                            showDoubleTapDisclaimer = false
                        },
                            contentPadding = PaddingValues(
                                vertical = 4.dp
                            )) {
                            Text(stringResource(R.string.accept))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDoubleTapDisclaimer = false },
                            contentPadding = PaddingValues(
                                vertical = 4.dp
                            )) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

        }
    }
}

@Composable
fun LearnedCommandsSection(
    device: RemoteDevice,
    repository: RemoteRepository,
    onKarooKeyAssigned: (AntRemoteKey, KarooKey?, PressType) -> Unit
) {


    LaunchedEffect(device.id) {
        if (device.learnedCommands.isEmpty()) {

            val defaultCommands = RemoteDevice.getDefaultLearnedCommands()
            defaultCommands.forEach { command ->
                repository.assignKeyCodeToCommand(
                    deviceId = device.id,
                    command = command.command,
                    karooKey = command.karooKey,
                    pressType = command.pressType
                )
            }
        }
    }


    val commands = remember(device.learnedCommands) {
        if (device.learnedCommands.isEmpty()) {
            RemoteDevice.getDefaultLearnedCommands()
        } else {
            device.learnedCommands
        }
    }

    val learnedCommands = remember(commands) {
        commands.map { it.command }.distinct()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.assign_karookey_to_commands),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (learnedCommands.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_learned_commands),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                learnedCommands.forEach { command ->
                    val singleCommand = commands.find {
                        it.command == command && it.pressType == PressType.SINGLE
                    } ?: LearnedCommand(command, PressType.SINGLE)

                    val doubleCommand = if (device.enabledDoubleTap) {
                        commands.find {
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
}

@Composable
fun CommandAssignmentRow(
    command: AntRemoteKey,
    singleCommand: LearnedCommand,
    doubleCommand: LearnedCommand?,
    onKarooKeyAssigned: (AntRemoteKey, KarooKey?, PressType) -> Unit
) {
    val context = LocalContext.current
    val noneString = stringResource(R.string.none)
    val options = remember {
        listOf(DropdownOption("null", noneString)) +
                KarooKey.entries.map { DropdownOption(it.name, it.getLabelString(context)) }
    }

    Column {
        Text(
            text = command.getLabel(),
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(8.dp))


        Column {

            val selectedOption = options.find {
                it.id == singleCommand.karooKey?.name
            } ?: options[0]

            KarooKeyDropdown(
                remotekey = stringResource(R.string.single_press),
                options = options,
                selectedOption = selectedOption,
                onSelect = { selected ->
                    val karooKey = if (selected.id == "null") null
                              else KarooKey.entries.find { it.name == selected.id }
                    onKarooKeyAssigned(command, karooKey, PressType.SINGLE)
                },

            )
        }

        doubleCommand?.let {
            Spacer(modifier = Modifier.height(12.dp))

            Column {

                val selectedOption = options.find {
                    it.id == doubleCommand.karooKey?.name
                } ?: options[0]

                KarooKeyDropdown(
                    remotekey = stringResource(R.string.double_press),
                    options = options,
                    selectedOption = selectedOption,
                    onSelect = { selected ->
                        val karooKey = if (selected.id == "null") null
                                  else KarooKey.entries.find { it.name == selected.id }
                        onKarooKeyAssigned(command, karooKey, PressType.DOUBLE)
                    },

                )
            }
        }
    }
}