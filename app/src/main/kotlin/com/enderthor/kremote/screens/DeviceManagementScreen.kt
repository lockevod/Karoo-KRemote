package com.enderthor.kremote.screens

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
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.ant.AntDeviceInfo
import com.enderthor.kremote.data.DeviceMessage
import com.enderthor.kremote.data.AntRemoteKey

@Composable
fun
    DeviceManagementScreen(
    devices: List<RemoteDevice>,
    availableAntDevices: List<AntDeviceInfo>,
    scanning: Boolean,
    message: DeviceMessage?,
    onScanClick: () -> Unit,
    onNewAntDeviceClick: (AntDeviceInfo) -> Unit,
    onMessageDismiss: () -> Unit,
    onDeviceDelete: (RemoteDevice) -> Unit,
    onDeviceClick: (RemoteDevice) -> Unit,
    learnedCommands: List<AntRemoteKey>, // Lista de comandos aprendidos
    onStartLearning: (RemoteDevice) -> Unit, // Función para iniciar el aprendizaje
    onStopLearning: () -> Unit, // Función para detener el aprendizaje
    onRestartLearning: (RemoteDevice) -> Unit //
) {
    var deviceToDelete by remember { mutableStateOf<RemoteDevice?>(null) }
    var selectedType by remember { mutableStateOf<RemoteType>(RemoteType.ANT) }
    var selectedDevice by remember { mutableStateOf<RemoteDevice?>(null) } // Dispositivo seleccionado

    LaunchedEffect(selectedDevice) {
        selectedDevice?.let {
            onStartLearning(it)
        }
    }

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
                onClick = { onScanClick() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning
            ) {
                Text(if (scanning) "Buscando..." else "Buscar ANT")
            }

            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Dispositivos disponibles


        if (availableAntDevices.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Dispositivos ANT+ disponibles",
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
                            text = "Dispositivo #${device.deviceNumber}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

        }

        // Dispositivos emparejados
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Dispositivos emparejados",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        val filteredDevices = devices.filter { it.type == selectedType }
        items(filteredDevices) { device ->
            DeviceItem(
                device = device,
                onClick = {
                    selectedDevice = device // Actualizar el dispositivo seleccionado
                    onDeviceClick(device)
                },
                onDeleteClick = { deviceToDelete = device }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Mostrar los comandos aprendidos
        item {
            Text("Comandos Aprendidos:")
            learnedCommands.forEach { command ->
                Text(command.label)  // Usar label en lugar de toString()
            }
        }

        // Botones para detener y reiniciar la búsqueda
        item {
            Row {
                Button(onClick = { onStopLearning() }) {
                    Text("Detener Búsqueda")
                }
                Button(onClick = { selectedDevice?.let { onRestartLearning(it) } }) {
                    Text("Reiniciar Búsqueda")
                }
            }
        }

    }

    // Diálogos
    deviceToDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            title = { Text("Eliminar dispositivo") },
            text = { Text("¿Estás seguro de que quieres eliminar ${device.name}?") },
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
    message?.let { msg ->
        AlertDialog(
            onDismissRequest = onMessageDismiss,
            title = {
                Text(
                    when (msg) {
                        is DeviceMessage.Error -> "Error"
                        is DeviceMessage.Success -> "Información"
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
                    Text("Aceptar")
                }
            }
        )
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
                        text = "ANT+",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (device.macAddress != null) {
                        Text(
                            text = device.macAddress,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // Nuevo: Mostrar número de comandos aprendidos
                    Text(
                        text = "${device.learnedCommands.size} comandos configurados",
                        style = MaterialTheme.typography.bodySmall
                    )
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
                    IconButton(onClick = onDeleteClick) {
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

