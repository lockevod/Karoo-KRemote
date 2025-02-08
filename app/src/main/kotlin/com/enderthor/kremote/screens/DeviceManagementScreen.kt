package com.enderthor.kremote.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.enderthor.kremote.bluetooth.BluetoothManager
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteType

@Composable
fun DeviceManagementScreen(
    devices: List<RemoteDevice>,
    availableDevices: List<BluetoothDevice>,
    scanning: Boolean,
    errorMessage: String?,
    onScanClick: () -> Unit,
    onDeviceClick: (RemoteDevice) -> Unit,
    onNewDeviceClick: (BluetoothDevice) -> Unit,
    onErrorDismiss: () -> Unit,
    onDeviceDelete: (RemoteDevice) -> Unit,
    bluetoothManager: BluetoothManager
) {
    var deviceToDelete by remember { mutableStateOf<RemoteDevice?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Button(
                onClick = onScanClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning
            ) {
                Text(if (scanning) "Buscando..." else "Buscar nuevos dispositivos")
            }

            if (scanning && availableDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Dispositivos encontrados",
                    style = MaterialTheme.typography.headlineSmall
                )
                ScannedDevicesList(
                    devices = availableDevices,
                    bluetoothManager = bluetoothManager,
                    onDeviceClick = onNewDeviceClick
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Dispositivos vinculados",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn {
                items(devices) { device ->
                    DeviceItem(
                        device = device,
                        onClick = { onDeviceClick(device) },
                        onDeleteClick = { deviceToDelete = device }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // Diálogo de confirmación de borrado
        deviceToDelete?.let { device ->
            AlertDialog(
                onDismissRequest = { deviceToDelete = null },
                title = { Text("Confirmar borrado") },
                text = { Text("¿Estás seguro de que quieres eliminar el dispositivo ${device.name}?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeviceDelete(device)
                            deviceToDelete = null
                        }
                    ) {
                        Text("Eliminar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deviceToDelete = null }) {
                        Text("Cancelar")
                    }
                }
            )
        }

        errorMessage?.let { error ->
            AlertDialog(
                onDismissRequest = onErrorDismiss,
                title = { Text("Atención") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = onErrorDismiss) {
                        Text("Aceptar")
                    }
                }
            )
        }

        if (scanning) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
fun ScannedDevicesList(
    devices: List<BluetoothDevice>,
    bluetoothManager: BluetoothManager,
    onDeviceClick: (BluetoothDevice) -> Unit
) {
    LazyColumn {
        items(devices) { device ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
                    .clickable { onDeviceClick(device) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = bluetoothManager.getDeviceName(device),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = bluetoothManager.getDeviceAddress(device),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: RemoteDevice,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onClick)
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when(device.type) {
                            RemoteType.BLUETOOTH -> "Bluetooth"
                            RemoteType.ANT -> "ANT+"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (device.macAddress != null) {
                        Text(
                            text = device.macAddress,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (device.isActive) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Text("Activo", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    IconButton(
                        onClick = onDeleteClick
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Eliminar dispositivo",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}