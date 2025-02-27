package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.viewmodel.ConfigurationViewModel
import com.enderthor.kremote.viewmodel.DeviceViewModel
import android.content.Context
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabLayout(
    antManager: AntManager,
    repository: RemoteRepository,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Mapping", "Remotes")

    // ViewModels
    val deviceViewModel: DeviceViewModel = viewModel(
        factory = DeviceViewModelFactory(antManager, repository, LocalContext.current)
    )

    val configViewModel: ConfigurationViewModel = viewModel(
        factory = ConfigViewModelFactory(repository)
    )

    // Obtener el dispositivo seleccionado para la pantalla de comandos
    val selectedDevice = deviceViewModel.selectedDevice.collectAsState()

    Column {
        // Si hay un dispositivo seleccionado, mostrar la pantalla de comandos
        selectedDevice.value?.let { device ->
            DeviceCommandsScreen(
                device = device,
                learnedCommands = deviceViewModel.learnedCommands.collectAsState().value,
                isLearning = deviceViewModel.scanning.collectAsState().value,
                onStartLearning = { deviceViewModel.startLearning() },
                onStopLearning = { deviceViewModel.stopLearning() },
                onRestartLearning = { deviceViewModel.restartLearning() },
                onNavigateBack = { deviceViewModel.clearSelectedDevice() },
                onClearAllCommands = { deviceViewModel.clearAllLearnedCommands() }
            )
        } ?: run {
            // Mostrar la pantalla normal con pestañas si no hay dispositivo seleccionado
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        text = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }

            when (selectedTab) {
                0 -> ConfigurationScreen(
                    devices = deviceViewModel.devices.collectAsState().value,
                    activeDevice = configViewModel.activeDevice.collectAsState().value,
                    errorMessage = configViewModel.errorMessage.collectAsState().value,
                    configViewModel = configViewModel
                )
                1 -> DeviceManagementScreen(
                    devices = deviceViewModel.devices.collectAsState().value,
                    availableAntDevices = deviceViewModel.availableAntDevices.collectAsState().value,
                    scanning = deviceViewModel.scanning.collectAsState().value,
                    message = deviceViewModel.message.collectAsState().value,
                    onScanClick = { deviceViewModel.startDeviceScan() },
                    onNewAntDeviceClick = { deviceInfo -> deviceViewModel.onNewAntDeviceSelected(deviceInfo) },
                    onMessageDismiss = { deviceViewModel.clearMessage() },
                    onDeviceDelete = { device -> deviceViewModel.removeDevice(device.id) },
                    onDeviceClick = { device -> deviceViewModel.activateDevice(device) },
                    onDeviceConfigure = { device -> deviceViewModel.onDeviceConfigureClick(device) }
                )
            }
        }
    }
}

// Factories se mantienen igual
class DeviceViewModelFactory(
    private val antManager: AntManager,
    private val repository: RemoteRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DeviceViewModel(antManager, repository,context) as T
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