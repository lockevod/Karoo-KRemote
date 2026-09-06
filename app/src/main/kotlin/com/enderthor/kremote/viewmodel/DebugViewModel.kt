package com.enderthor.kremote.viewmodel



import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.ReconnectionManagerSingleton
import com.enderthor.kremote.utils.ConnectionState
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.utils.HeartbeatManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

    // Estado real de conexión, siguiendo en vivo al ReconnectionManager.
    //
    // Antes, si el mapa del manager venía vacío se fabricaba `isConnected = true` para cada
    // dispositivo registrado. Pero "mapa vacío" es precisamente el síntoma de que el
    // monitoreo no arrancó: la pantalla de diagnóstico pintaba verde justo en el fallo que
    // existe para revelar. Ahora un dispositivo sin estado se muestra como desconectado.
    //
    // Además se observa el singleton como flujo (no un `.value` puntual), así que la
    // pantalla se actualiza cuando el servicio arranca después de abrirla y cuando el
    // manager cambia de estado, sin depender de que se re-emita la lista de dispositivos.
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionStates: Flow<Map<Int, ConnectionState>> =
        combine(_registeredDevices, ReconnectionManagerSingleton.instance, ::Pair)
            .flatMapLatest { (devices, manager) ->
                val statesFlow = manager?.connectionStates ?: MutableStateFlow(emptyMap())
                statesFlow.map { managerStates ->
                    devices.associate { device ->
                        val deviceId = device.antDeviceId ?: 0
                        deviceId to (managerStates[deviceId] ?: ConnectionState(
                            deviceNumber = deviceId,
                            isConnected = false,
                            isReconnecting = false,
                            lastConnectionAttempt = 0L,
                            reconnectAttempts = 0,
                            lastError = null
                        ))
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

        // (Se eliminó el diagnóstico que registraba si el singleton era null en construcción:
        // desde que connectionStates lo observa como StateFlow, esa foto puntual no significa
        // nada — la llegada tardía del servicio ya se maneja sola.)
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
