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
import com.enderthor.kremote.utils.DebugLogger
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
    private var learningTimeoutJob: Job? = null


    private companion object {
        const val LEARNING_TIMEOUT_MS = 30_000L // 30 segundos
    }

    init {
        viewModelScope.launch {
            repository.getDevices().collect {
                _devices.value = it
            }
        }

        antManager.setupCommandCallback { command, _ ->
            // pressType descartado a propósito: aprendemos botones, no pulsaciones.
            if (scanning.value) {
                onCommandDetected(command)
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

                // Fix: en lugar de polling cada 1 s, recoger el StateFlow directamente
                // con un timeout de 30 s (igual que antes)
                kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    antManager.detectedDevices.collect { devices ->
                        _availableAntDevices.value = devices
                    }
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
        DebugLogger.logDeviceDetection(deviceInfo.deviceNumber, deviceInfo.name, "DeviceViewModel")

        viewModelScope.launch {
            try {

                val existingDevice = devices.value.find { it.antDeviceId == deviceInfo.deviceNumber }

                if (existingDevice != null) {

                    DebugLogger.logConnectionEvent(
                        deviceNumber = deviceInfo.deviceNumber,
                        event = "DEVICE_ALREADY_EXISTS",
                        details = "Device ${deviceInfo.name} already registered, activating existing device",
                        source = "DeviceViewModel"
                    )
                    _message.value = DeviceMessage.Success(getString(R.string.device_already_exists))
                    repository.setActiveDevice(existingDevice.id)
                } else {

                    val deviceId = UUID.randomUUID().toString()
                    val newDevice = RemoteDevice(
                        id = deviceId,
                        name = deviceInfo.name,
                        type = RemoteType.ANT,
                        antDeviceId = deviceInfo.deviceNumber,
                        macAddress = deviceInfo.deviceNumber.toString()
                    )

                    DebugLogger.logConnectionEvent(
                        deviceNumber = deviceInfo.deviceNumber,
                        event = "NEW_DEVICE_REGISTERED",
                        details = "Registering new ANT+ device: ${deviceInfo.name} with ID: $deviceId",
                        source = "DeviceViewModel"
                    )

                    repository.addDevice(newDevice)
                    _message.value = DeviceMessage.Success(getString(R.string.remote_registered_successfully))
                    repository.setActiveDevice(deviceId)

                    DebugLogger.logConnectionEvent(
                        deviceNumber = deviceInfo.deviceNumber,
                        event = "DEVICE_ACTIVATED",
                        details = "New device ${deviceInfo.name} registered and activated successfully",
                        source = "DeviceViewModel"
                    )
                }


                _availableAntDevices.value = _availableAntDevices.value.filter {
                    it.deviceNumber != deviceInfo.deviceNumber
                }
            } catch (e: Exception) {
                Timber.e(e, "Error adding new ANT+ device")
                DebugLogger.logError("DEVICE_REGISTRATION", "Failed to register device: ${deviceInfo.name}", e, "DeviceViewModel")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun startLearning() {
        Timber.d("🎓 [DeviceViewModel] INICIO - startLearning() llamado")
        DebugLogger.logConnectionEvent(0, "LEARNING_MODE_START", "Learning mode activated for device: ${selectedDevice.value?.name}", "DeviceViewModel")
        
        _scanning.value = true
        _learnedCommands.value = emptyList()


        antManager.setLearningMode(true)


        val sharedPrefs = appContext.getSharedPreferences("kremote_state", Context.MODE_PRIVATE)
        sharedPrefs.edit {
            putBoolean("learning_mode", true)
            // Marca de tiempo: si el proceso muere a mitad del aprendizaje, el flag quedaría
            // a true y la extensión dejaría de ejecutar acciones para siempre. La extensión
            // ignora un flag más viejo que LEARNING_MODE_MAX_MS.
            putLong("learning_mode_ts", System.currentTimeMillis())
        }

        Timber.d("🎓 [DeviceViewModel] Learning mode ACTIVATED and synced with extension")
        DebugLogger.logConnectionEvent(0, "LEARNING_MODE_SYNC", "Learning mode synchronized with extension via SharedPrefs", "DeviceViewModel")
        

        startCommandListener()


        startLearningTimeout()
        DebugLogger.logConnectionEvent(0, "LEARNING_TIMEOUT_SET", "Auto-stop timeout set to 30 seconds", "DeviceViewModel")
    }

    fun stopLearning() {
        Timber.d("🎓 [DeviceViewModel] INICIO - stopLearning() llamado")
        DebugLogger.logConnectionEvent(0, "LEARNING_MODE_STOP", "Learning mode deactivated. Learned commands: ${_learnedCommands.value.size}", "DeviceViewModel")
        
        _scanning.value = false


        antManager.setLearningMode(false)


        val sharedPrefs = appContext.getSharedPreferences("kremote_state", Context.MODE_PRIVATE)
        sharedPrefs.edit {
            putBoolean("learning_mode", false)
        }

        Timber.d("🎓 [DeviceViewModel] Learning mode DEACTIVATED and synced with extension")
        DebugLogger.logConnectionEvent(0, "LEARNING_MODE_DEACTIVATED", "Learning deactivated and synced with extension", "DeviceViewModel")

        // Fix: cada comando ya se persiste en onCommandDetected vía repository.updateLearnedCommand
        // con la pressType correcta. La llamada redundante a saveLearnedCommands() sobraría
        // (y usaba pressType.SINGLE por defecto para todos). Eliminada.
        stopCommandListener()

        learningTimeoutJob?.cancel()
        DebugLogger.logConnectionEvent(0, "LEARNING_TIMEOUT_CANCELLED", "Auto-timeout job cancelled", "DeviceViewModel")
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


    private var commandPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun startCommandListener() {
        val sharedPrefsCommands = appContext.getSharedPreferences("kremote_learned_commands", Context.MODE_PRIVATE)

        // Fix: sustituir el polling de 500 ms por un listener de SharedPreferences
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "timestamp" && _scanning.value) {
                val commandName = prefs.getString("last_command", null)
                if (!commandName.isNullOrEmpty()) {
                    try {
                        val command = AntRemoteKey.valueOf(commandName)
                        Timber.d("📥 [DeviceViewModel] Button received from extension: $commandName")
                        onCommandDetected(command)
                    } catch (e: Exception) {
                        Timber.e(e, "Error procesando comando recibido: $commandName")
                    }
                }
            }
        }
        commandPrefsListener = listener
        sharedPrefsCommands.registerOnSharedPreferenceChangeListener(listener)

        // Lectura inicial para no perder un comando ya escrito antes de registrar el listener
        val commandName = sharedPrefsCommands.getString("last_command", null)
        if (!commandName.isNullOrEmpty()) {
            try {
                onCommandDetected(AntRemoteKey.valueOf(commandName))
            } catch (e: Exception) {
                Timber.e(e, "Error procesando comando inicial: $commandName")
            }
        }
    }

    private fun stopCommandListener() {
        commandPrefsListener?.let { listener ->
            appContext.getSharedPreferences("kremote_learned_commands", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(listener)
        }
        commandPrefsListener = null
    }

    /**
     * La pantalla puede morir sin pasar por [stopLearning] (botón atrás, cambio de tab, muerte
     * de la Activity). La limpieza no puede depender de que la UI lo pida.
     *
     * Importa más desde que la extensión respeta el modo aprendizaje: ahora SÍ escribe botones
     * en `kremote_learned_commands` durante su ventana de 120 s. Sin esto, el listener sobrevive
     * hasta que lo recoja el GC sobre un ViewModel ya muerto con `_scanning` a true y el
     * `viewModelScope` cancelado — comandos procesados contra el `selectedDevice` anterior con
     * resultado dependiente del timing. Publicar `learning_mode=false` corta además la ventana
     * de la extensión de inmediato, sin esperar a que expire su red de seguridad.
     */
    override fun onCleared() {
        stopCommandListener()
        learningTimeoutJob?.cancel()
        _scanning.value = false
        antManager.setLearningMode(false)
        try {
            appContext.getSharedPreferences("kremote_state", Context.MODE_PRIVATE).edit {
                putBoolean("learning_mode", false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error clearing learning mode on ViewModel clear")
        }
        super.onCleared()
    }

    /**
     * Un botón del mando acaba de emitir. El aprendizaje descubre BOTONES: la pulsación
     * simple o doble no se aprende, se configura después (cada botón descubierto muestra
     * su fila SINGLE y su fila DOUBLE en la pantalla de configuración). Por eso aquí no
     * entra ningún pressType — deduplicar por botón es la semántica correcta, no un
     * descuido.
     *
     * Descartarlo también evita una fila DOUBLE espuria: durante un ESCANEO el AntManager
     * no está en modo aprendizaje, así que un doble toque real llegaba como DOUBLE y se
     * persistía contra el `selectedDevice` que hubiera quedado de una configuración previa.
     */
    private fun onCommandDetected(command: AntRemoteKey) {
        DebugLogger.logKeyEvent(
            deviceNumber = selectedDevice.value?.antDeviceId ?: 0,
            command = command.name,
            pressType = "LEARN",
            processed = true,
            source = "DeviceViewModel"
        )

        if (!_learnedCommands.value.contains(command)) {
            _learnedCommands.value = _learnedCommands.value + command
            DebugLogger.logConnectionEvent(
                deviceNumber = selectedDevice.value?.antDeviceId ?: 0,
                event = "COMMAND_LEARNED",
                details = "New button learned: ${command.name}. Total buttons: ${_learnedCommands.value.size}",
                source = "DeviceViewModel"
            )

            selectedDevice.value?.let { device ->
                viewModelScope.launch {
                    try {
                        repository.updateLearnedCommand(device.id, command)
                        _message.value = DeviceMessage.Success(
                            getString(R.string.command_learned, command.getLabelString(appContext))
                        )
                        Timber.d("✅ [DeviceViewModel] Botón aprendido guardado: %s", command.name)
                        DebugLogger.logConnectionEvent(
                            deviceNumber = device.antDeviceId ?: 0,
                            event = "COMMAND_SAVED_TO_DB",
                            details = "Command ${command.name} saved to database for device ${device.name}",
                            source = "DeviceViewModel"
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Error saving learned command")
                        DebugLogger.logError("LEARNING", "Failed to save learned command: ${command.name}", e, "DeviceViewModel")
                    }
                }
            }
        } else {
            DebugLogger.logKeyEvent(
                deviceNumber = selectedDevice.value?.antDeviceId ?: 0,
                command = command.name,
                pressType = "LEARN",
                processed = false,
                source = "DeviceViewModel"
            )
        }
    }

    // NUEVO: Función para iniciar el job de timeout
    private fun startLearningTimeout() {
        learningTimeoutJob = viewModelScope.launch {
            delay(LEARNING_TIMEOUT_MS)

            // Si aún estamos en modo de aprendizaje, detenerlo automáticamente
            if (_scanning.value) {
                Timber.d("⏰ [DeviceViewModel] Timeout reached, stopping learning mode automatically")
                DebugLogger.logConnectionEvent(
                    deviceNumber = 0,
                    event = "LEARNING_TIMEOUT_REACHED",
                    details = "Learning mode auto-stopped after 30 seconds. Commands learned: ${_learnedCommands.value.size}",
                    source = "DeviceViewModel"
                )
                stopLearning()
            }
        }
    }
}