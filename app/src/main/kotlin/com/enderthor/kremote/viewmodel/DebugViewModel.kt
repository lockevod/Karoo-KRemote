package com.enderthor.kremote.viewmodel



import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.ReconnectionManagerSingleton
import com.enderthor.kremote.utils.ConnectionState
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.utils.HeartbeatManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber


class DebugViewModel(
    private val repository: RemoteRepository
) : ViewModel() {

    private val _isDebugEnabled = MutableStateFlow(DebugLogger.isEnabled())
    val isDebugEnabled: StateFlow<Boolean> = _isDebugEnabled.asStateFlow()

    private val _logContent = MutableStateFlow("")


    // Estados de conexión del ReconnectionManager
    private val reconnectionManagerStates = ReconnectionManagerSingleton.getInstance()?.connectionStates
        ?: MutableStateFlow<Map<Int, ConnectionState>>(emptyMap()).asStateFlow()

    // Dispositivos registrados desde el repositorio
    private val _registeredDevices = MutableStateFlow<List<RemoteDevice>>(emptyList())


    // Combinar ambos estados para mostrar información completa
    val connectionStates = combine(
        reconnectionManagerStates,
        _registeredDevices
    ) { managerStates, devices ->
        // Si tenemos estados del ReconnectionManager, usarlos
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

    init {
        // Cargar dispositivos registrados
        viewModelScope.launch {
            repository.getDevices().collect { devices ->
                _registeredDevices.value = devices
                DebugLogger.logConnectionEvent(0, "DEVICES_LOADED", "Loaded ${devices.size} registered devices: ${devices.map { "${it.name}(#${it.antDeviceId})" }}", "DebugViewModel")
            }
        }

        refreshLog()

        // DIAGNÓSTICO EXTENDIDO: Verificar estado completo del singleton
        val reconnectionManager = ReconnectionManagerSingleton.getInstance()
        if (reconnectionManager != null) {
            DebugLogger.logConnectionEvent(0, "DEBUG_VIEWMODEL_INIT", "ReconnectionManager singleton found", "DebugViewModel")
            Timber.d("[DebugViewModel] ReconnectionManager singleton encontrado")

            // NUEVO: Verificar si hay estados de conexión activos
            viewModelScope.launch {
                reconnectionManager.connectionStates.collect { states ->
                    DebugLogger.logConnectionEvent(0, "CONNECTION_STATES_UPDATE", "States count: ${states.size}, devices: ${states.keys.toList()}", "DebugViewModel")
                    Timber.d("[DebugViewModel] Estados de conexión actualizados: ${states.size} dispositivos monitoreados")
                }
            }
        } else {
            DebugLogger.logConnectionEvent(0, "DEBUG_VIEWMODEL_INIT", "ReconnectionManager singleton is NULL - usando datos del repositorio", "DebugViewModel")
            Timber.w("[DebugViewModel] ReconnectionManager singleton es NULL - mostrando dispositivos registrados en su lugar")
        }

        // NUEVO: Verificación activa puntual cuando se accede a la pantalla de debug
        performPeriodicActiveCheck()
    }

    /**
     * NUEVO: Realiza verificaciones activas puntuales de conexión
     * Solo se ejecuta cuando se accede a la pantalla de debug y cada 10 minutos
     */
    private fun performPeriodicActiveCheck() {
        viewModelScope.launch {
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
     * NUEVO: Ejecuta verificaciones activas para todos los dispositivos registrados
     * MEJORADO: Usar HeartbeatManager en lugar de PerformanceOptimizer directamente
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
                        // MEJORADO: Usar HeartbeatManager en lugar de PerformanceOptimizer directamente
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

            refreshLog()
        }
    }

    fun refreshLog() {
        viewModelScope.launch {
            try {
                val content = DebugLogger.getLogContent()
                _logContent.value = content
            } catch (e: Exception) {
                Timber.e(e, "Error refreshing log content")
                _logContent.value = "Error cargando logs: ${e.message}"
            }
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            try {
                DebugLogger.clearLog()
                _logContent.value = ""
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

    // Funciones de limpieza y configuración de debug eliminadas para reducir warnings
    // Las funciones de heartbeat se mantienen disponibles a través de HeartbeatManager directamente
}
