// Kotlin
package com.enderthor.kremote.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.painterResource
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
    configViewModel: ConfigurationViewModel,
    onNavigateBack: () -> Unit
) {
    var selectedDeviceId by remember { mutableStateOf(activeDevice?.id) }
    val selectedDevice = devices.find { it.id == selectedDeviceId }
    val onlyWhileRiding by configViewModel.onlyWhileRiding.collectAsState()
    val forcedScreenOn by configViewModel.forcedScreenOn.collectAsState()
    var showDoubleTapDisclaimer by remember { mutableStateOf(false) }
    var tempDeviceId by remember { mutableStateOf("") }

    val context = LocalContext.current
    val repository = remember { RemoteRepository(context) }
    Box(modifier = Modifier.fillMaxSize()) {
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
                                    val roundedValue = (value / 100f).toInt() * 100L
                                    configViewModel.updateDoubleTapTimeout(device.id, roundedValue)
                                },
                                valueRange = 1000f..2200f,
                                steps = 24, // 100ms
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
        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = "Back",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 16.dp)
                .size(54.dp)
                .clickable {
                    onNavigateBack()
                }
        )
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