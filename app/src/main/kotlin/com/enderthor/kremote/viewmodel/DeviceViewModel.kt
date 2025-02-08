package com.enderthor.kremote.viewmodel

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.bluetooth.BluetoothManager
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteSettings
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.permissions.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

class DeviceViewModel(
    private val bluetoothManager: BluetoothManager,
    private val antManager: AntManager,
    private val repository: RemoteRepository,
    private val permissionManager: PermissionManager
) : ViewModel() {
    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices

    private val _availableDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothDevice>> = _availableDevices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    init {
        loadDevices()
        observeBluetoothScanner()
    }

    private fun loadDevices() {
        viewModelScope.launch {
            repository.currentConfig.collect { config ->
                _devices.value = config.devices
            }
        }
    }

    private fun observeBluetoothScanner() {
        viewModelScope.launch {
            bluetoothManager.scanResults.collect { devices ->
                _availableDevices.value = devices
            }
        }
    }

    fun startBluetoothScan() {
        if (permissionManager.areBluetoothPermissionsGranted()) {
            startScanWithPermissions()
        } else {
            permissionManager.requestBluetoothPermissions(
                onGranted = { startScanWithPermissions() },
                onDenied = {
                    _errorMessage.value = "Se necesitan permisos de Bluetooth para buscar dispositivos"
                }
            )
        }
    }

    private fun startScanWithPermissions() {
        permissionManager.checkBluetoothEnabled(
            onEnabled = {
                _scanning.value = true
                bluetoothManager.startScan()
                viewModelScope.launch {
                    delay(30000) // 30 segundos de escaneo
                    stopScan()
                }
            },
            onDisabled = {
                _errorMessage.value = "El Bluetooth debe estar activado para buscar dispositivos"
            }
        )
    }

    fun stopScan() {
        _scanning.value = false
        bluetoothManager.stopScan()
    }

    fun onDeviceSelected(device: RemoteDevice) {
        viewModelScope.launch {
            if (device.type == RemoteType.BLUETOOTH && !permissionManager.areBluetoothPermissionsGranted()) {
                permissionManager.requestBluetoothPermissions(
                    onGranted = { activateDevice(device) },
                    onDenied = {
                        _errorMessage.value = "Se necesitan permisos de Bluetooth para usar este dispositivo"
                    }
                )
            } else {
                activateDevice(device)
            }
        }
    }

    fun onNewBluetoothDeviceSelected(bluetoothDevice: BluetoothDevice) {
        viewModelScope.launch {
            try {
                stopScan()

                val newDevice = RemoteDevice(
                    id = UUID.randomUUID().toString(),
                    name = bluetoothManager.getDeviceName(bluetoothDevice),
                    type = RemoteType.BLUETOOTH,
                    isActive = false,
                    keyMappings = RemoteSettings(),
                    macAddress = bluetoothManager.getDeviceAddress(bluetoothDevice)
                )

                val existingDevice = _devices.value.find { it.macAddress == newDevice.macAddress }
                if (existingDevice != null) {
                    _errorMessage.value = "Este dispositivo ya está vinculado"
                    return@launch
                }

                if (connectToDevice(newDevice)) {
                    repository.addDevice(newDevice)
                    _errorMessage.value = "Dispositivo añadido correctamente"
                } else {
                    _errorMessage.value = "No se pudo conectar con el dispositivo"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error al añadir el dispositivo: ${e.message}"
            }
        }
    }

    private suspend fun connectToDevice(device: RemoteDevice): Boolean {
        return when (device.type) {
            RemoteType.BLUETOOTH -> {
                if (!bluetoothManager.isBluetoothEnabled()) {
                    _errorMessage.value = "El Bluetooth debe estar activado"
                    false
                } else {
                    bluetoothManager.connectToDevice(device)
                    true
                }
            }
            RemoteType.ANT -> {
                antManager.connect()
                true
            }
        }
    }

    private fun activateDevice(device: RemoteDevice) {
        viewModelScope.launch {
            try {
                // Desactivar el dispositivo actual si existe
                _devices.value.find { it.isActive }?.let { activeDevice ->
                    when (activeDevice.type) {
                        RemoteType.BLUETOOTH -> bluetoothManager.disconnectDevice(activeDevice.id)
                        RemoteType.ANT -> antManager.disconnect()
                    }
                }

                // Activar el nuevo dispositivo
                repository.setActiveDevice(device.id)

                // Conectar el nuevo dispositivo
                if (!connectToDevice(device)) {
                    _errorMessage.value = "No se pudo conectar con el dispositivo"
                    repository.setActiveDevice("") // Desactivar si falla la conexión
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error al activar el dispositivo: ${e.message}"
                repository.setActiveDevice("") // Desactivar en caso de error
            }
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                val device = _devices.value.find { it.id == deviceId }
                when (device?.type) {
                    RemoteType.BLUETOOTH -> bluetoothManager.disconnectDevice(deviceId)
                    RemoteType.ANT -> antManager.disconnect()
                    null -> { /* No hacer nada si el dispositivo no existe */ }
                }
                repository.removeDevice(deviceId)
            } catch (e: Exception) {
                _errorMessage.value = "Error al eliminar el dispositivo: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        viewModelScope.launch {
            _devices.value.forEach { device ->
                when (device.type) {
                    RemoteType.BLUETOOTH -> bluetoothManager.disconnectDevice(device.id)
                    RemoteType.ANT -> antManager.disconnect()
                }
            }
        }
    }
}