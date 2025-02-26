package com.enderthor.kremote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.ant.AntDeviceInfo
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.data.DeviceMessage
import com.enderthor.kremote.data.AntRemoteKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID

class DeviceViewModel(
    private val antManager: AntManager,
    private val repository: RemoteRepository,
) : ViewModel() {
    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _message = MutableStateFlow<DeviceMessage?>(null)
    val message: StateFlow<DeviceMessage?> = _message

    private val _availableAntDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val availableAntDevices: StateFlow<List<AntDeviceInfo>> = _availableAntDevices


    private val _learnedCommands = MutableStateFlow<List<AntRemoteKey>>(emptyList())  // Cambiado a AntRemoteKey
    val learnedCommands: StateFlow<List<AntRemoteKey>> = _learnedCommands.asStateFlow()


    private var learningJob: Job? = null

    init {
        loadDevices()
        observeAntDevices()
    }



    fun startLearning() {
        learningJob?.cancel() // Cancelar cualquier búsqueda anterior
        _learnedCommands.value = emptyList() // Limpiar la lista de comandos aprendidos
        learningJob = viewModelScope.launch {
            try {
                antManager.startDeviceSearch()
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 20000) { // 20 segundos
                    delay(100) // Esperar un poco antes de verificar si hay nuevos comandos
                }
                stopLearning()
            } catch (e: Exception) {
                _message.value = DeviceMessage.Error("Error learning commands: ${e.message}")
                Timber.e(e, "Error learning commands")
            }
        }
    }

    fun stopLearning() {
        learningJob?.cancel()
        antManager.stopScan()
    }

    fun restartLearning() {
        stopLearning()
        startLearning()
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
                _message.value = DeviceMessage.Error("Error cargando dispositivos: ${e.message}")
            }
        }
    }

    private fun observeAntDevices() {
        viewModelScope.launch {
            antManager.detectedDevices.collect { devices ->
                _availableAntDevices.value = devices
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
                    learnedCommands =mutableListOf(),
                    macAddress = antDevice.deviceNumber.toString()
                )

                val existingDevice = _devices.value.find {
                    it.type == RemoteType.ANT &&
                            it.macAddress == antDevice.deviceNumber.toString()
                }

                if (existingDevice != null) {
                    _message.value = DeviceMessage.Error("Dispositivo ya emparejado")
                    return@launch
                }

                Timber.d("Añadiendo dispositivo ANT+: $newDevice")
                repository.deactivateAllDevices()
                repository.addDevice(newDevice)
                repository.setActiveDevice(newDevice.id)

                _message.value = DeviceMessage.Success("Dispositivo añadido y activado")
                loadDevices()

                Timber.d("Dispositivo ANT+ añadido correctamente")
            } catch (e: Exception) {
                _message.value = DeviceMessage.Error("Error al añadir dispositivo: ${e.message}")
                Timber.e(e, "Error añadiendo dispositivo ANT+")
            }
        }
    }

    fun startDeviceScan() {
        viewModelScope.launch {
            try {
                _scanning.value = true
                startAntScan()
            } catch (e: Exception) {
                Timber.e(e, "Error starting device scan")
                _message.value = DeviceMessage.Error("Error scanning devices: ${e.message}")
                _scanning.value = false
            }
        }
    }

    fun startAntScan() {
        try {
            _scanning.value = true
            _availableAntDevices.value = emptyList()
            antManager.startDeviceSearch()
            viewModelScope.launch {
                delay(30000)
                stopScanAnt()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting ANT+ scan")
            _message.value = DeviceMessage.Error("Error scanning ANT+ devices: ${e.message}")
            throw e
        }
    }

    fun stopScanAnt() {
        try {
            _scanning.value = false
            antManager.stopScan()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping ANT+ scan")
            _message.value = DeviceMessage.Error("Error stopping scan: ${e.message}")
        }
    }

    fun stopScan() {
        viewModelScope.launch {
            try {
                stopScanAnt()
            } catch (e: Exception) {
                Timber.e(e, "Error stopping scan")
                _message.value = DeviceMessage.Error("Error stopping scan: ${e.message}")
            }
        }
    }

   fun activateDevice(device: RemoteDevice) {
       viewModelScope.launch {
           try {
               repository.setActiveDevice(device.id)
           } catch (e: Exception) {
               _message.value = DeviceMessage.Error("Error activating device: ${e.message}")
               Timber.e(e, "Error activating device")
           }
       }
   }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                antManager.disconnect()
                repository.removeDevice(deviceId)
                _message.value = DeviceMessage.Success("Dispositivo eliminado correctamente")
            } catch (e: Exception) {
                _message.value = DeviceMessage.Error("Error al eliminar el dispositivo: ${e.message}")
                Timber.e(e, "Error removing device")
            }
        }
    }


    fun clearMessage() {
        _message.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}