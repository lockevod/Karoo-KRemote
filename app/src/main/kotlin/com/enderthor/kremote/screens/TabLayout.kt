package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enderthor.kremote.viewmodel.DeviceViewModel
import com.enderthor.kremote.bluetooth.BluetoothManager
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.permissions.PermissionManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel

@Composable
fun TabLayout(
    bluetoothManager: BluetoothManager,
    antManager: AntManager,
    repository: RemoteRepository,
    permissionManager: PermissionManager
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Configuración", "Dispositivos", "Ajustes")

    val viewModel: DeviceViewModel = viewModel(
        factory = DeviceViewModelFactory(
            bluetoothManager = bluetoothManager,
            antManager = antManager,
            repository = repository,
            permissionManager = permissionManager
        )
    )

    val devices by viewModel.devices.collectAsState()
    val availableDevices by viewModel.availableDevices.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

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
            0 -> ConfigurationScreen()
            1 -> DeviceManagementScreen(
                devices = devices,
                availableDevices = availableDevices,
                scanning = scanning,
                errorMessage = errorMessage,
                onScanClick = { viewModel.startBluetoothScan() },
                onDeviceClick = { device -> viewModel.onDeviceSelected(device) },
                onNewDeviceClick = { device -> viewModel.onNewBluetoothDeviceSelected(device) },
                onErrorDismiss = { viewModel.clearError() },
                onDeviceDelete = { device -> viewModel.removeDevice(device.id) },
                bluetoothManager = bluetoothManager
            )
            2 -> SettingsScreen()
        }
    }
}

class DeviceViewModelFactory(
    private val bluetoothManager: BluetoothManager,
    private val antManager: AntManager,
    private val repository: RemoteRepository,
    private val permissionManager: PermissionManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DeviceViewModel(
                bluetoothManager = bluetoothManager,
                antManager = antManager,
                repository = repository,
                permissionManager = permissionManager
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}