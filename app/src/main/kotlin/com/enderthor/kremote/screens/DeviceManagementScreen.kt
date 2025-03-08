package com.enderthor.kremote.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.ant.AntDeviceInfo
import com.enderthor.kremote.data.DeviceMessage
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.R

@Composable
fun DeviceItem(
    device: RemoteDevice,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onConfigureClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (device.isActive) 4.dp else 1.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )


            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${device.learnedCommands.count { it.pressType == PressType.SINGLE }} LC",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (device.isActive) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Text(stringResource(R.string.active_device), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }


                Row {
                    IconButton(onClick = onConfigureClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.configure_commands),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete_device),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceManagementScreen(
    devices: List<RemoteDevice>,
    availableAntDevices: List<AntDeviceInfo>,
    scanning: Boolean,
    message: DeviceMessage?,
    onScanClick: () -> Unit,
    onNewAntDeviceClick: (AntDeviceInfo) -> Unit,
    onMessageDismiss: () -> Unit,
    onDeviceDelete: (RemoteDevice) -> Unit,
    onDeviceClick: (RemoteDevice) -> Unit,
    onDeviceConfigure: (RemoteDevice) -> Unit,
    onNavigateBack: () -> Unit
) {
    var deviceToDelete by remember { mutableStateOf<RemoteDevice?>(null) }
    var selectedType by remember { mutableStateOf<RemoteType>(RemoteType.ANT) }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Selector de tipo
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(
                        selected = selectedType == RemoteType.ANT,
                        onClick = { selectedType = RemoteType.ANT },
                        label = { Text("ANT+") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onScanClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !scanning
                ) {
                    Text(if (scanning) stringResource(R.string.searching) else stringResource(R.string.search_ant))
                }

                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }


            if (availableAntDevices.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.available_ant_devices),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                items(availableAntDevices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                            .clickable { onNewAntDeviceClick(device) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = device.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.device_number, device.deviceNumber),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }


            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.paired_devices),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            val filteredDevices = devices.filter { it.type == selectedType }
            items(filteredDevices) { device ->
                DeviceItem(
                    device = device,
                    onClick = { onDeviceClick(device) },
                    onDeleteClick = { deviceToDelete = device },
                    onConfigureClick = { onDeviceConfigure(device) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }


        }


        deviceToDelete?.let { device ->
            AlertDialog(
                onDismissRequest = { deviceToDelete = null },
                title = { Text(stringResource(R.string.delete_device_title)) },
                text = { Text(stringResource(R.string.delete_device_confirmation, device.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        onDeviceDelete(device)
                        deviceToDelete = null
                    }) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deviceToDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        message?.let { msg ->
            AlertDialog(
                onDismissRequest = onMessageDismiss,
                title = {
                    Text(
                        when (msg) {
                            is DeviceMessage.Error -> stringResource(R.string.error)
                            is DeviceMessage.Success -> stringResource(R.string.information)
                        }
                    )
                },
                text = {
                    Text(
                        when (msg) {
                            is DeviceMessage.Error -> msg.message
                            is DeviceMessage.Success -> msg.message
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = onMessageDismiss) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }
        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = "Atrás",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCommandsScreen(
    device: RemoteDevice,
    learnedCommands: List<AntRemoteKey>,
    isLearning: Boolean,
    onStartLearning: () -> Unit,
    onStopLearning: () -> Unit,
    onRestartLearning: () -> Unit,
    onNavigateBack: () -> Unit,
    onClearAllCommands: () -> Unit
) {

    var learningStartTime by remember { mutableStateOf<Long?>(null) }


    LaunchedEffect(isLearning) {
        learningStartTime = if (isLearning) System.currentTimeMillis() else null
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.configure_device, device.name)) },
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.learning_mode),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(
                                R.string.status,
                                if (isLearning) stringResource(R.string.awaiting_signal)
                                else stringResource(R.string.ready_to_learn)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isLearning) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isLearning) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))


                            learningStartTime?.let { startTime ->
                                val elapsed by produceState(initialValue = 0L, key1 = startTime) {
                                    while (true) {
                                        value = System.currentTimeMillis() - startTime
                                        delay(1000)
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.elapsed_time, elapsed / 1000),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.start_learning_instructions),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))


                        if (isLearning) {
                            Button(
                                onClick = onStopLearning,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(stringResource(R.string.stop_learning))
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = onRestartLearning,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.restart_learning))
                            }
                        } else {
                            Button(
                                onClick = onStartLearning,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.start_learning))
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = onClearAllCommands,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(stringResource(R.string.clear_all_commands))
                            }
                        }
                    }
                }


                if (learnedCommands.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                stringResource(R.string.detected_commands),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            LazyColumn(
                                modifier = Modifier.heightIn(max = 200.dp)
                            ) {
                                items(learnedCommands) { command ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = command.label,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = "Code: ${command.gCommand}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.saved_commands, device.learnedCommands.count { it.pressType == PressType.SINGLE }),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (device.learnedCommands.isEmpty()) {
                            Text(
                                stringResource(R.string.no_saved_commands),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) {
                                val singlePressCommands = device.learnedCommands.filter { it.pressType == PressType.SINGLE }

                                items(singlePressCommands) { learnedCommand ->
                                    ListItem(
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = null
                                            )
                                        },
                                        headlineContent = {
                                            Text(
                                                text = learnedCommand.command.label,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        },
                                        supportingContent = {
                                            Text(
                                                text =  learnedCommand.karooKey?.let { "Assigned" } ?: "No assigned",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
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