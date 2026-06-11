package com.enderthor.kremote.viewmodel



import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.ReconnectionManagerSingleton
import com.enderthor.kremote.utils.ConnectionState
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.utils.HeartbeatManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber


class DebugViewModel(
    private val repository: RemoteRepository
) : ViewModel() {

    private val _isDebugEnabled = MutableStateFlow(DebugLogger.isEnabled())
    val isDebugEnabled: StateFlow<Boolean> = _isDebugEnabled.asStateFlow()

    // Fix: resolver el singleton de forma diferida dentro del flow para que no quede
    // fijo a null si el servicio no había arrancado aún en el momento de construcción.
    private val _registeredDevices = MutableStateFlow<List<RemoteDevice>>(emptyList())

    // Combinar ambos estados para mostrar información completa
    val connectionStates = _registeredDevices.map { devices ->
        // Resolver el singleton en cada emisión para capturarlo si ya está disponible
        val managerStates = ReconnectionManagerSingleton.getInstance()?.connectionStates?.value
            ?: emptyMap()
        managerStates.ifEmpty {
            // Si no hay estados del manager, crear estados simulados basados en dispositivos registrados
            devices.associate { device ->
                val deviceId = device.antDeviceId ?: 0
                deviceId to ConnectionState(
                    deviceNumber = deviceId,
                    isConnected = true, // Asumimos conectado si el dispositivo está registrado
                    isReconnecting = false,
                    lastConnectionAttempt = System.currentTimeMillis(),
                    reconnectAttempts = 0,
                    lastError = null
                )
            }
        }
    }

    // Job de la verificación periódica — controlado desde la pantalla de debug
    private var periodicCheckJob: Job? = null

    init {
        // Cargar dispositivos registrados
        viewModelScope.launch {
            repository.getDevices().collect { devices ->
                _registeredDevices.value = devices
                if (DebugLogger.isEnabled()) {
                    DebugLogger.logConnectionEvent(0, "DEVICES_LOADED", "Loaded ${devices.size} registered devices: ${devices.map { "${it.name}(#${it.antDeviceId})" }}", "DebugViewModel")
                }
            }
        }

        // DIAGNÓSTICO EXTENDIDO: Verificar estado completo del singleton
        val reconnectionManager = ReconnectionManagerSingleton.getInstance()
        if (reconnectionManager != null) {
            if (DebugLogger.isEnabled()) {
                DebugLogger.logConnectionEvent(0, "DEBUG_VIEWMODEL_INIT", "ReconnectionManager singleton found", "DebugViewModel")
            }
            Timber.d("[DebugViewModel] ReconnectionManager singleton encontrado")
        } else {
            if (DebugLogger.isEnabled()) {
                DebugLogger.logConnectionEvent(0, "DEBUG_VIEWMODEL_INIT", "ReconnectionManager singleton is NULL - usando datos del repositorio", "DebugViewModel")
            }
            Timber.w("[DebugViewModel] ReconnectionManager singleton es NULL - mostrando dispositivos registrados en su lugar")
        }
    }

    /**
     * Inicia las verificaciones activas periódicas.
     * Llamar desde DebugScreen al entrar (LaunchedEffect / DisposableEffect).
     */
    fun startPeriodicActiveCheck() {
        if (periodicCheckJob?.isActive == true) return
        periodicCheckJob = viewModelScope.launch {
            // Verificación inmediata al acceder a la pantalla
            performActiveConnectionChecks("debug_screen_access")

            // Verificación cada 10 minutos mientras la pantalla está activa
            while (true) {
                kotlinx.coroutines.delay(600_000L) // 10 minutos
                if (_isDebugEnabled.value) {
                    performActiveConnectionChecks("periodic_check")
                }
            }
        }
    }

    /**
     * Detiene las verificaciones periódicas.
     * Llamar desde DebugScreen al salir.
     */
    fun stopPeriodicActiveCheck() {
        periodicCheckJob?.cancel()
        periodicCheckJob = null
    }

    /**
     *  Ejecuta verificaciones activas para todos los dispositivos registrados
     *  Usar HeartbeatManager en lugar de PerformanceOptimizer directamente
     */
    private suspend fun performActiveConnectionChecks(reason: String) {
        val reconnectionManager = ReconnectionManagerSingleton.getInstance()
        val devices = _registeredDevices.value

        if (reconnectionManager != null && devices.isNotEmpty()) {
            val antManager = reconnectionManager.getAntManager()
            DebugLogger.logConnectionEvent(0, "ACTIVE_CHECKS_START", "Starting active checks for ${devices.size} devices. Reason: $reason", "DebugViewModel")

            devices.forEach { device ->
                device.antDeviceId?.let { deviceId ->
                    try {
                        //  Usar HeartbeatManager en lugar de PerformanceOptimizer directamente
                        val isConnected = HeartbeatManager.performDebugScreenCheck(deviceId, antManager)

                        // Actualizar estado si es diferente del actual
                        val currentState = reconnectionManager.connectionStates.value[deviceId]
                        if (currentState?.isConnected != isConnected) {
                            DebugLogger.logConnectionEvent(
                                deviceId,
                                "CONNECTION_STATE_CORRECTED",
                                "State updated from active check: ${currentState?.isConnected} -> $isConnected",
                                "DebugViewModel"
                            )
                        }
                    } catch (e: Exception) {
                        DebugLogger.logError("ACTIVE_CHECK", "Error checking device $deviceId", e, "DebugViewModel")
                    }
                }
            }
        } else {
            DebugLogger.logConnectionEvent(0, "ACTIVE_CHECKS_SKIPPED", "ReconnectionManager or devices not available", "DebugViewModel")
        }
    }

    fun setDebugEnabled(enabled: Boolean) {
        viewModelScope.launch {
            DebugLogger.setEnabled(enabled)
            _isDebugEnabled.value = enabled

            if (enabled) {
                DebugLogger.logConnectionEvent(0, "DEBUG_ENABLED", "Debug logging activated by user")
            }
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            try {
                // Fix: DebugLogger.clearLog() escribe en disco → mover a IO
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DebugLogger.clearLog()
                }
                DebugLogger.logConnectionEvent(0, "LOG_CLEARED", "Debug log cleared by user")
            } catch (e: Exception) {
                Timber.e(e, "Error clearing log")
            }
        }
    }




    fun forceReconnect(deviceId: Int) {
        viewModelScope.launch {
            val reconnectionManager = ReconnectionManagerSingleton.getInstance()
            if (reconnectionManager != null) {
                reconnectionManager.forceReconnect(deviceId)
                DebugLogger.logConnectionEvent(deviceId, "FORCE_RECONNECT_REQUESTED", "User requested force reconnect")
            } else {
                DebugLogger.logConnectionEvent(deviceId, "FORCE_RECONNECT_FAILED", "ReconnectionManager not available")
            }
        }
    }

}
