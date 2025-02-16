package com.enderthor.kremote.viewmodel

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.ant.AntDeviceInfo
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.EXTENSION_NAME
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteSettings
import com.enderthor.kremote.data.RemoteType
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ReleaseAnt
import io.hammerhead.karooext.models.RequestAnt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.UUID

class DeviceViewModel(
    private val antManager: AntManager,
    private val repository: RemoteRepository,
    private val karooSystem: KarooSystemService
) : ViewModel() {
    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices

    private val _availableDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothDevice>> = _availableDevices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _availableAntDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val availableAntDevices: StateFlow<List<AntDeviceInfo>> = _availableAntDevices

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadDevices()
        observeAntDevices()
    }

    private fun observeAntDevices() {
        viewModelScope.launch {
            antManager.detectedDevices.collect { devices ->
                Timber.d("ANT devices updated: $devices")
                _availableAntDevices.value = devices
            }
        }
    }


    private fun loadDevices() {
        viewModelScope.launch {
            try {
                repository.currentConfig.collect { config ->
                    Timber.d("DeviceViewModel - Cargando dispositivos: ${config.devices}")
                    _devices.value = config.devices
                    Timber.d("DeviceViewModel - Dispositivos actualizados: ${_devices.value}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error cargando dispositivos")
                _errorMessage.value = "Error cargando dispositivos: ${e.message}"
            }
        }
    }


    fun onNewAntDeviceSelected(antDevice: AntDeviceInfo) {
        viewModelScope.launch {
            try {
                stopScanAnt()

                val newDevice = RemoteDevice(
                    id = UUID.randomUUID().toString(),
                    name = antDevice.name,
                    type = RemoteType.ANT,
                    isActive = false,
                    keyMappings = RemoteSettings(),
                    macAddress = antDevice.deviceNumber.toString()
                )

                val existingDevice = _devices.value.find {
                    it.type == RemoteType.ANT &&
                            it.macAddress == antDevice.deviceNumber.toString()
                }

                if (existingDevice != null) {
                    _errorMessage.value = "Dispositivo ya emparejado"
                    return@launch
                }

                // Añadir logs para depuración
                Timber.d("Añadiendo dispositivo ANT+: $newDevice")

                // Desactivar otros dispositivos antes de añadir el nuevo
                repository.deactivateAllDevices()

                // Añadir y activar el nuevo dispositivo
                repository.addDevice(newDevice)
                repository.setActiveDevice(newDevice.id)

                _errorMessage.value = "Dispositivo añadido y activado"

                // Forzar actualización de la lista
                loadDevices()

                Timber.d("Dispositivo ANT+ añadido correctamente")
            } catch (e: Exception) {
                _errorMessage.value = "Error al añadir dispositivo: ${e.message}"
                Timber.e(e, "Error añadiendo dispositivo ANT+")
            }
        }
    }



    fun requestConnection(enable: Boolean) {

        val request =  RequestAnt(EXTENSION_NAME)
        val release =  ReleaseAnt(EXTENSION_NAME)

        if (enable) {
            karooSystem.dispatch(request)
        } else {
            karooSystem.dispatch(release)
        }

    }
    fun startDeviceScan(type: RemoteType) {
        viewModelScope.launch {
            try {

                _scanning.value = true
                requestConnection(true)
                startAntScan()


            } catch (e: Exception) {
                Timber.e(e, "Error starting device scan")
                _errorMessage.value = "Error scanning devices: ${e.message}"
                _scanning.value = false
            }
        }
    }

    fun startAntScan() {
        try {
            _scanning.value = true
            _availableAntDevices.value = emptyList() // Limpiar lista anterior
            antManager.startDeviceSearch()
            viewModelScope.launch {
                delay(30000) // 30 segundos de escaneo
                stopScanAnt()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting ANT+ scan")
            _errorMessage.value = "Error scanning ANT+ devices: ${e.message}"
            throw e
        }
    }


    fun stopScan() {
        _scanning.value = false
        requestConnection(false)
    }

    fun stopScanAnt() {
        _scanning.value = false
        antManager.stopScan()
        requestConnection(false)
    }


    fun onDeviceSelected(device: RemoteDevice) {
        viewModelScope.launch {

            activateDevice(device)
        }
    }

    private fun handleKeyPress(keyCode: Int) {
        viewModelScope.launch {
            try {
                Timber.d("Procesando tecla: $keyCode")
                // Aquí puedes implementar la lógica para manejar las pulsaciones de teclas
                // Por ejemplo, actualizar la UI o ejecutar alguna acción específica
            } catch (e: Exception) {
                Timber.e(e, "Error procesando tecla: ${e.message}")
                _errorMessage.value = "Error procesando tecla: ${e.message}"
            }
        }
    }


    private fun activateDevice(device: RemoteDevice) {
        viewModelScope.launch {
            try {
                repository.setActiveDevice(device.id)
            } catch (e: Exception) {
                _errorMessage.value = "Error activating device: ${e.message}"
                Timber.e(e, "Error activating device")
            }
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                antManager.disconnect()
                repository.removeDevice(deviceId)
            } catch (e: Exception) {
                _errorMessage.value = "Error removing device: ${e.message}"
                Timber.e(e, "Error removing device")
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}