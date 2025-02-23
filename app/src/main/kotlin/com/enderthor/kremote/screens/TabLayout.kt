package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enderthor.kremote.viewmodel.DeviceViewModel
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.RemoteRepository

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import com.enderthor.kremote.viewmodel.ConfigurationViewModel
import io.hammerhead.karooext.KarooSystemService

@Composable
fun TabLayout(
    antManager: AntManager,
    repository: RemoteRepository,
    karooSystem: KarooSystemService
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Conf.", "Remotes", "General")

    val deviceViewModel: DeviceViewModel = viewModel(
        factory = DeviceViewModelFactory(
            antManager = antManager,
            repository = repository,
            karooSystem = karooSystem
        )
    )

    val configViewModel: ConfigurationViewModel = viewModel(
        factory = ConfigViewModelFactory(
            repository = repository,
        )
    )


    val availableAntDevices by deviceViewModel.availableAntDevices.collectAsState()
    val devices by deviceViewModel.devices.collectAsState()
    val scanning by deviceViewModel.scanning.collectAsState()
    val errorMessage_device by deviceViewModel.errorMessage.collectAsState()



    val activeDevice by configViewModel.activeDevice.collectAsState()
    val errorMessage_config by configViewModel.errorMessage.collectAsState()

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
                    activeDevice = activeDevice,
                    errorMessage = errorMessage_config,
                    configViewModel = configViewModel
            )
            1 -> DeviceManagementScreen(
                devices = devices,
                scanning = scanning,
                errorMessage = errorMessage_device,
                onScanClick = { type -> deviceViewModel.startDeviceScan(type) },
                onDeviceClick = { device -> deviceViewModel.onDeviceSelected(device) },
                availableAntDevices = availableAntDevices,
                onNewAntDeviceClick = { device -> deviceViewModel.onNewAntDeviceSelected(device) },
                onErrorDismiss = { deviceViewModel.clearError() },
                onDeviceDelete = { device -> deviceViewModel.removeDevice(device.id) },

            )
        }
    }
}

class DeviceViewModelFactory(
    private val antManager: AntManager,
    private val repository: RemoteRepository,
    private val karooSystem: KarooSystemService
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DeviceViewModel(
                antManager = antManager,
                repository = repository,
                karooSystem = karooSystem
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class ConfigViewModelFactory(
    private val repository: RemoteRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ConfigurationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ConfigurationViewModel(
                repository = repository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
