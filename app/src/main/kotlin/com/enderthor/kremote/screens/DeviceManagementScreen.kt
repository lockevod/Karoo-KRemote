package com.enderthor.kremote.screens


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.ant.AntDeviceInfo
import com.enderthor.kremote.data.DeviceMessage
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
    onDeviceConfigure: (RemoteDevice) -> Unit
) {
    var deviceToDelete by remember { mutableStateOf<RemoteDevice?>(null) }
    var selectedType by remember { mutableStateOf(RemoteType.ANT) }
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
    }
}
