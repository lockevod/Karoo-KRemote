// Kotlin
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


@Composable
fun TabLayout(
    antManager: AntManager,
    repository: RemoteRepository,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Mapping", "Remotes")

    // ViewModels
    val deviceViewModel: DeviceViewModel = viewModel(
        factory = DeviceViewModelFactory(antManager, repository)
    )

    val configViewModel: ConfigurationViewModel = viewModel(
        factory = ConfigViewModelFactory(repository)
    )

    // Estados
    val deviceStates = with(deviceViewModel) {
        Triple(
            availableAntDevices.collectAsState(),
            devices.collectAsState(),
            scanning.collectAsState()
        )
    }

    val configStates = with(configViewModel) {
        Pair(
            activeDevice.collectAsState(),
            errorMessage.collectAsState()
        )
    }

    Column {
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
                devices = deviceStates.second.value,
                activeDevice = configStates.first.value,
                errorMessage = configStates.second.value,
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
                learnedCommands = deviceViewModel.learnedCommands.collectAsState().value, // Pasar la lista de comandos aprendidos
                onStartLearning = { device -> deviceViewModel.startLearning() }, // Pasar la función para iniciar el aprendizaje
                onStopLearning = { deviceViewModel.stopLearning() }, // Pasar la función para detener el aprendizaje
                onRestartLearning = { device -> deviceViewModel.restartLearning() }  )
        }
    }
}

class DeviceViewModelFactory(
    private val antManager: AntManager,
    private val repository: RemoteRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DeviceViewModel(antManager, repository) as T
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