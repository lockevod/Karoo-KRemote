package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.viewmodel.ConfigurationViewModel
import com.enderthor.kremote.viewmodel.DeviceViewModel
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.enderthor.kremote.R



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabLayout(
    antManager: AntManager,
    repository: RemoteRepository
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.tab_mapping),
        stringResource(R.string.tab_remotes),
        stringResource(R.string.tab_debug)
    )

    // ViewModels
    val deviceViewModel: DeviceViewModel = viewModel(
        factory = DeviceViewModelFactory(antManager, repository, LocalContext.current)
    )

    val configViewModel: ConfigurationViewModel = viewModel(
        factory = ConfigViewModelFactory(repository)
    )

    // Collect states from ViewModels
    val devices by deviceViewModel.devices.collectAsState()
    val availableAntDevices by deviceViewModel.availableAntDevices.collectAsState()
    val scanning by deviceViewModel.scanning.collectAsState()
    val message by deviceViewModel.message.collectAsState()
    val selectedDevice by deviceViewModel.selectedDevice.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }
        // Contenedor con altura fija para manejar scroll interno
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> {
                    // Pestaña de Configuración - NO debe mostrar dispositivos seleccionados
                    // Aquí solo configuración global y mapeos de dispositivos activos
                    ConfigurationScreen(
                        devices = devices,
                        activeDevice = devices.firstOrNull { it.isActive },
                        errorMessage = (message as? com.enderthor.kremote.data.DeviceMessage.Error)?.message,
                        configViewModel = configViewModel
                    )
                }
                1 -> {
                    // Si hay un dispositivo seleccionado para configurar teclas, mostrar pantalla de aprendizaje
                    selectedDevice?.let { device ->
                        val learnedCommands by deviceViewModel.learnedCommands.collectAsState()

                        // Estado para el diálogo de confirmación (dentro del contexto del dispositivo)
                        var showClearCommandsDialog by remember { mutableStateOf(false) }

                        // Pantalla de aprendizaje con scroll
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.learning_title, device.name),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(stringResource(R.string.learning_instructions))

                            Spacer(modifier = Modifier.height(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { deviceViewModel.startLearning() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.start_learning))
                                }
                                Button(
                                    onClick = { deviceViewModel.stopLearning() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.stop_learning))
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            if (learnedCommands.isNotEmpty()) {
                                Text(stringResource(R.string.commands_detected), style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                learnedCommands.forEach { command ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    ) {
                                        Text("✅ ${command.name}", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))

                                // NUEVO: Botón para borrar comandos aprendidos (después de mostrar comandos)
                                Button(
                                    onClick = {
                                        showClearCommandsDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text(stringResource(R.string.clear_learned_commands))
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            } else if (scanning) {
                                Text(stringResource(R.string.waiting_commands), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            Button(
                                onClick = { deviceViewModel.clearSelectedDevice(); deviceViewModel.stopLearning() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.back_to_devices))
                            }
                        }

                        // Diálogo de confirmación (dentro del contexto del dispositivo)
                        if (showClearCommandsDialog) {
                            AlertDialog(
                                onDismissRequest = { showClearCommandsDialog = false },
                                title = { Text(stringResource(R.string.clear_learned_commands)) },
                                text = { Text(stringResource(R.string.clear_commands_confirmation)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            deviceViewModel.clearLearnedCommands()
                                            showClearCommandsDialog = false
                                        }
                                    ) {
                                        Text(stringResource(R.string.ok))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showClearCommandsDialog = false }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                    } ?: run {
                        // Lista normal de dispositivos
                        DeviceManagementScreen(
                            devices = devices,
                            availableAntDevices = availableAntDevices,
                            scanning = scanning,
                            message = message,
                            onScanClick = { deviceViewModel.startDeviceScan() },
                            onNewAntDeviceClick = { antDevice ->
                                deviceViewModel.onNewAntDeviceSelected(antDevice)
                            },
                            onMessageDismiss = { deviceViewModel.clearMessage() },
                            onDeviceDelete = { device ->
                                deviceViewModel.removeDevice(device.id)
                            },
                            onDeviceClick = { device ->
                                deviceViewModel.activateDevice(device)
                            },
                            onDeviceConfigure = { device ->
                                // CORRECTO: Seleccionar dispositivo para configurar teclas (NO cambiar pestaña)
                                deviceViewModel.onDeviceConfigureClick(device)
                            }
                        )
                    }
                }
                2 -> {
                    DebugScreen(repository = repository)
                }
            }
        }
    }
}


class DeviceViewModelFactory(
    private val antManager: AntManager,
    private val repository: RemoteRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DeviceViewModel(antManager, repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class ConfigViewModelFactory(
    private val repository: RemoteRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ConfigurationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ConfigurationViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}