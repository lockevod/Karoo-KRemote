package com.enderthor.kremote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import com.enderthor.kremote.ant.AntDeviceInfo
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.data.DeviceMessage
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import android.content.Context
import com.enderthor.kremote.data.getLabelString
import java.util.UUID

class DeviceViewModel(
    private val antManager: AntManager,
    private val repository: RemoteRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<RemoteDevice?>(null)
    val selectedDevice: StateFlow<RemoteDevice?> = _selectedDevice.asStateFlow()

    private val _availableAntDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val availableAntDevices: StateFlow<List<AntDeviceInfo>> = _availableAntDevices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _message = MutableStateFlow<DeviceMessage?>(null)
    val message: StateFlow<DeviceMessage?> = _message.asStateFlow()

    private val _learnedCommands = MutableStateFlow<List<AntRemoteKey>>(emptyList())
    val learnedCommands: StateFlow<List<AntRemoteKey>> = _learnedCommands.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getDevices().collect {
                _devices.value = it
            }
        }

        antManager.setupCommandCallback { command, pressType ->
            if (scanning.value) {
                onCommandDetected(command, pressType)
            }
        }
    }

    private fun getString(resId: Int): String = appContext.getString(resId)
    private fun getString(resId: Int, vararg formatArgs: Any): String = appContext.getString(resId, *formatArgs)


    fun clearSelectedDevice() {
        _selectedDevice.value = null
        _learnedCommands.value = emptyList()
    }

    fun startDeviceScan() {
        _scanning.value = true
        _availableAntDevices.value = emptyList()

        scanJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    antManager.startDeviceSearch()
                }

                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 30000) {
                    _availableAntDevices.value = antManager.detectedDevices.value
                    delay(1000)
                }
            } catch (e: CancellationException) {
                Timber.d("Scan job cancelled $e")
            } catch (e: Exception) {
                Timber.e(e, "Error during device scan")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            } finally {
                _scanning.value = false
                try {
                    antManager.stopScan()
                } catch (e: Exception) {
                    Timber.e(e, "Error stopping scan")
                }
            }
        }
    }

    fun onDeviceConfigureClick(device: RemoteDevice) {
        _selectedDevice.value = device


        viewModelScope.launch {
            try {
                device.antDeviceId?.let { deviceId ->
                    withContext(Dispatchers.IO) {
                        antManager.connect(deviceId)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error connecting to ANT+ device for configuration")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun activateDevice(device: RemoteDevice) {
        viewModelScope.launch {
            try {
                repository.setActiveDevice(device.id)
            } catch (e: Exception) {
                Timber.e(e, "Error activating device")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                repository.removeDevice(deviceId)
                _message.value = DeviceMessage.Success(getString(R.string.device_deleted))
            } catch (e: Exception) {
                Timber.e(e, "Error removing device")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun onNewAntDeviceSelected(deviceInfo: AntDeviceInfo) {
        _scanning.value = false
        scanJob?.cancel()

        viewModelScope.launch {
            try {
                // Comprobar si ya existe un dispositivo con el mismo número ANT+
                val existingDevice = devices.value.find { it.antDeviceId == deviceInfo.deviceNumber }

                if (existingDevice != null) {
                    // Si el dispositivo ya existe, simplemente actívalo
                    _message.value = DeviceMessage.Success(getString(R.string.device_already_exists))
                    repository.setActiveDevice(existingDevice.id)
                } else {
                    // Si no existe, crea un nuevo dispositivo
                    val deviceId = UUID.randomUUID().toString()
                    val newDevice = RemoteDevice(
                        id = deviceId,
                        name = deviceInfo.name,
                        type = RemoteType.ANT,
                        antDeviceId = deviceInfo.deviceNumber,
                        macAddress = deviceInfo.deviceNumber.toString()
                    )

                    repository.addDevice(newDevice)
                    _message.value = DeviceMessage.Success(getString(R.string.remote_registered_successfully))
                    repository.setActiveDevice(deviceId)
                }

                // Actualiza la lista de dispositivos disponibles
                _availableAntDevices.value = _availableAntDevices.value.filter {
                    it.deviceNumber != deviceInfo.deviceNumber
                }
            } catch (e: Exception) {
                Timber.e(e, "Error adding new ANT+ device")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun startLearning() {
        _scanning.value = true
        _learnedCommands.value = emptyList()

        // Activar en la instancia local de AntManager
        antManager.setLearningMode(true)

        // NUEVO: Sincronizar con la extensión usando SharedPreferences
        val sharedPrefs = appContext.getSharedPreferences("kremote_state", Context.MODE_PRIVATE)
        sharedPrefs.edit {
            putBoolean("learning_mode", true)
        }

        Timber.d("🎓 [DeviceViewModel] Modo aprendizaje ACTIVADO y sincronizado con extensión")
        
        // NUEVO: Iniciar monitoreo de comandos desde la extensión
        startCommandListener()
    }

    fun stopLearning() {
        _scanning.value = false

        // Desactivar en la instancia local
        antManager.setLearningMode(false)

        // NUEVO: Sincronizar con la extensión
        val sharedPrefs = appContext.getSharedPreferences("kremote_state", Context.MODE_PRIVATE)
        sharedPrefs.edit {
            putBoolean("learning_mode", false)
        }

        Timber.d("🎓 [DeviceViewModel] Modo aprendizaje DESACTIVADO y sincronizado con extensión")

        saveLearnedCommands()
    }

    fun clearLearnedCommands() {
        selectedDevice.value?.let { device ->
            viewModelScope.launch {
                try {
                    repository.clearDeviceCommands(device.id)
                    _message.value = DeviceMessage.Success(getString(R.string.commands_cleared))
                    Timber.d("🗑️ [DeviceViewModel] Comandos aprendidos borrados para dispositivo: ${device.name}")
                } catch (e: Exception) {
                    Timber.e(e, "Error clearing learned commands for device: ${device.name}")
                    _message.value = DeviceMessage.Error(getString(R.string.error_clearing_commands, e.message ?: "Unknown error"))
                }
            }
        } ?: run {
            _message.value = DeviceMessage.Error(getString(R.string.no_device_selected))
        }
    }

    // NUEVO: Función para escuchar comandos desde la extensión
    private fun startCommandListener() {
        viewModelScope.launch {
            val sharedPrefsCommands = appContext.getSharedPreferences("kremote_learned_commands", Context.MODE_PRIVATE)
            var lastTimestamp = 0L
            
            while (_scanning.value) {
                try {
                    val currentTimestamp = sharedPrefsCommands.getLong("timestamp", 0L)
                    
                    if (currentTimestamp > lastTimestamp) {
                        val commandName = sharedPrefsCommands.getString("last_command", null)
                        val pressTypeName = sharedPrefsCommands.getString("last_press_type", "SINGLE")
                        
                        if (!commandName.isNullOrEmpty() && !pressTypeName.isNullOrEmpty()) {
                            try {
                                val command = AntRemoteKey.valueOf(commandName)
                                val pressType = PressType.valueOf(pressTypeName)
                                
                                Timber.d("📥 [DeviceViewModel] Comando recibido desde extensión: $commandName ($pressTypeName)")
                                onCommandDetected(command, pressType)
                                
                                lastTimestamp = currentTimestamp
                            } catch (e: Exception) {
                                Timber.e(e, "Error procesando comando recibido: $commandName")
                            }
                        }
                    }
                    
                    delay(500) // Verificar cada 500ms
                } catch (e: Exception) {
                    Timber.e(e, "Error en listener de comandos")
                    delay(1000)
                }
            }
        }
    }

    private fun onCommandDetected(command: AntRemoteKey, pressType: PressType = PressType.SINGLE) {

        if (!_learnedCommands.value.contains(command)) {
            _learnedCommands.value = _learnedCommands.value + command

            selectedDevice.value?.let { device ->
                viewModelScope.launch {
                    try {

                        repository.updateLearnedCommand(device.id, command, pressType)
                        _message.value = DeviceMessage.Success(
                            getString(R.string.command_learned, command.getLabelString(appContext))
                        )
                        Timber.d("✅ [DeviceViewModel] Comando aprendido guardado: %s (%s)", command.name, pressType.name)
                    } catch (e: Exception) {
                        Timber.e(e, "Error saving learned command")
                    }
                }
            }
        }
    }

    private fun saveLearnedCommands() {
        selectedDevice.value?.let { device ->
            viewModelScope.launch {
                try {
                    for (command in _learnedCommands.value) {
                        repository.updateLearnedCommand(device.id, command)
                        Timber.d("💾 [DeviceViewModel] Comando aprendido persisted: %s", command.name)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error saving learned commands")
                    _message.value = DeviceMessage.Error(getString(R.string.error))
                }
            }
        }
    }
}