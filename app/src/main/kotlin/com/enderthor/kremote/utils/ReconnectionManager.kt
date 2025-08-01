package com.enderthor.kremote.utils

import com.enderthor.kremote.ant.AntManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.min
import kotlin.math.pow

data class ConnectionState(
    val deviceNumber: Int,
    val isConnected: Boolean,
    val isReconnecting: Boolean,
    val lastConnectionAttempt: Long,
    val reconnectAttempts: Int,
    val lastError: String? = null
)

class ReconnectionManager(
    private val antManager: AntManager,
    private val scope: CoroutineScope
) {
    private val _connectionStates = MutableStateFlow<Map<Int, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<Int, ConnectionState>> = _connectionStates.asStateFlow()

    private val reconnectionJobs = mutableMapOf<Int, Job>()
    private val monitoringJobs = mutableMapOf<Int, Job>()

    // Acceso público al AntManager para heartbeat
    fun getAntManager(): AntManager = antManager

    // Configuración mejorada con PerformanceOptimizer
    private val baseReconnectDelay = 2000L
    private val maxReconnectDelay = 30000L
    private val maxReconnectAttempts = 15
    private val connectionCheckInterval = 10000L
    private val connectionTimeout = 15000L

    init {
        // Inicializar limpieza periódica usando HeartbeatManager
        HeartbeatManager.setupPeriodicCleanup(scope)
    }

    fun startMonitoring(deviceNumber: Int) {
        DebugLogger.logConnectionEvent(deviceNumber, "START_MONITORING", "Iniciando monitoreo para dispositivo #$deviceNumber", "ReconnectionManager")
        Timber.d("[ReconnectionManager] 🔍 Iniciando monitoreo para dispositivo #$deviceNumber")

        // Cancelar monitoring anterior si existe
        monitoringJobs[deviceNumber]?.cancel()
        DebugLogger.logConnectionEvent(deviceNumber, "PREVIOUS_MONITORING_CANCELLED", "Cancelado monitoreo anterior si existía", "ReconnectionManager")

        // MEJORADO: Registrar listener para eventos ANT+ reales (Opción B)
        val antEventListener: (Boolean) -> Unit = { isConnected ->
            scope.launch {
                val currentState = _connectionStates.value[deviceNumber]
                if (currentState != null && currentState.isConnected != isConnected) {
                    DebugLogger.logConnectionEvent(
                        deviceNumber,
                        "ANT_REAL_EVENT_PROCESSED",
                        "Real ANT+ event processed by ReconnectionManager: $isConnected",
                        "ReconnectionManager"
                    )

                    if (isConnected) {
                        // Conexión recuperada por evento real ANT+
                        updateConnectionState(deviceNumber) {
                            it.copy(
                                isConnected = true,
                                isReconnecting = false,
                                reconnectAttempts = 0,
                                lastError = null
                            )
                        }
                        // Cancelar intentos de reconexión
                        reconnectionJobs[deviceNumber]?.cancel()
                    } else {
                        // Desconexión detectada por evento real ANT+
                        updateConnectionState(deviceNumber) {
                            it.copy(isConnected = false)
                        }
                        // Solo iniciar reconexión si no estamos ya reconectando
                        if (!currentState.isReconnecting) {
                            startReconnection(deviceNumber)
                        }
                    }
                }
            }
        }

        HeartbeatManager.registerConnectionListener(deviceNumber, antEventListener)

        // Inicializar estado usando caché del PerformanceOptimizer
        val initialState = PerformanceOptimizer.getCachedOrCreate("connection_state_$deviceNumber") {
            ConnectionState(
                deviceNumber = deviceNumber,
                isConnected = false,
                isReconnecting = false,
                lastConnectionAttempt = 0L,
                reconnectAttempts = 0
            )
        }

        updateConnectionState(deviceNumber) { initialState }
        DebugLogger.logConnectionEvent(deviceNumber, "INITIAL_STATE_SET", "Estado inicial establecido: $initialState", "ReconnectionManager")

        monitoringJobs[deviceNumber] = scope.launch {
            DebugLogger.logConnectionEvent(deviceNumber, "MONITORING_LOOP_STARTED", "Bucle de monitoreo iniciado", "ReconnectionManager")
            Timber.d("[ReconnectionManager] 🔄 Bucle de monitoreo iniciado para dispositivo #$deviceNumber")

            while (isActive) {
                try {
                    // MEJORADO: Usar HeartbeatManager para verificación inteligente
                    val shouldVerify = HeartbeatManager.shouldVerifyConnection(deviceNumber)
                    DebugLogger.logConnectionEvent(deviceNumber, "HEARTBEAT_CHECK", "shouldVerify: $shouldVerify", "ReconnectionManager")

                    if (shouldVerify) {
                        // OPCIÓN C: Usar verificación UI inteligente con caché
                        val isConnected = HeartbeatManager.performUICheck(deviceNumber, antManager, "reconnection_manager")
                        val currentState = _connectionStates.value[deviceNumber]

                        DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_VERIFICATION", "isConnected: $isConnected, currentState: $currentState", "ReconnectionManager")

                        if (currentState != null) {
                            if (!isConnected && currentState.isConnected && !currentState.isReconnecting) {
                                // Conexión perdida, iniciar reconexión
                                DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_LOST", "Starting reconnection process")
                                Timber.w("[ReconnectionManager] 🔴 Connection lost for device #$deviceNumber - starting reconnection")
                                startReconnection(deviceNumber)
                            } else if (isConnected && !currentState.isConnected) {
                                // Conexión recuperada
                                DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_RECOVERED")
                                Timber.i("[ReconnectionManager] 🟢 Connection recovered for device #$deviceNumber")
                                PerformanceOptimizer.recordDeviceReconnection(deviceNumber)
                                updateConnectionState(deviceNumber) {
                                    it.copy(
                                        isConnected = true,
                                        isReconnecting = false,
                                        reconnectAttempts = 0,
                                        lastError = null
                                    )
                                }
                                // Cancelar intentos de reconexión
                                reconnectionJobs[deviceNumber]?.cancel()
                            }
                        }
                    } else {
                        // Si no necesitamos verificar, pero tenemos estado conocido de eventos ANT+, usarlo
                        val lastKnownState = HeartbeatManager.getLastKnownState(deviceNumber)
                        if (lastKnownState != null) {
                            val currentState = _connectionStates.value[deviceNumber]
                            if (currentState != null && currentState.isConnected != lastKnownState) {
                                DebugLogger.logConnectionEvent(
                                    deviceNumber,
                                    "STATE_SYNC_FROM_ANT_EVENTS",
                                    "Syncing state from ANT+ events: $lastKnownState"
                                )
                                updateConnectionState(deviceNumber) {
                                    it.copy(isConnected = lastKnownState)
                                }
                            }
                        }
                    }

                    // Usar intervalo adaptativo del HeartbeatManager
                    val optimalInterval = PerformanceOptimizer.getOptimalVerificationInterval(deviceNumber)
                    DebugLogger.logConnectionEvent(deviceNumber, "MONITORING_INTERVAL", "Próxima verificación en ${optimalInterval}ms", "ReconnectionManager")
                    delay(optimalInterval)

                } catch (e: Exception) {
                    DebugLogger.logError("MONITORING", "Error monitoring device $deviceNumber", e)
                    Timber.e(e, "[ReconnectionManager] ❌ Error monitoreando dispositivo #$deviceNumber")
                    delay(connectionCheckInterval)
                }
            }

            DebugLogger.logConnectionEvent(deviceNumber, "MONITORING_LOOP_ENDED", "Bucle de monitoreo terminado", "ReconnectionManager")
            Timber.d("[ReconnectionManager] 🛑 Bucle de monitoreo terminado para dispositivo #$deviceNumber")
        }

        DebugLogger.logConnectionEvent(deviceNumber, "MONITORING_JOB_CREATED", "Job de monitoreo creado y almacenado", "ReconnectionManager")
        Timber.d("[ReconnectionManager] ✅ Monitoreo configurado correctamente para dispositivo #$deviceNumber")
    }

    private fun startReconnection(deviceNumber: Int) {
        // Cancelar reconexión anterior si existe
        reconnectionJobs[deviceNumber]?.cancel()

        reconnectionJobs[deviceNumber] = scope.launch {
            var attempts = 0

            updateConnectionState(deviceNumber) {
                it.copy(isReconnecting = true, isConnected = false)
            }

            while (attempts < maxReconnectAttempts && isActive) {
                attempts++

                // Usar PerformanceOptimizer para calcular delay optimizado
                val delay = PerformanceOptimizer.getOptimizedDelay(
                    calculateBackoffDelay(attempts),
                    loadFactor = if (attempts > 5) 1.5f else 1.0f // Aumentar delay si hay muchos fallos
                )

                DebugLogger.logReconnectionEvent(deviceNumber, attempts, maxReconnectAttempts, delay, false)

                updateConnectionState(deviceNumber) {
                    it.copy(
                        reconnectAttempts = attempts,
                        lastConnectionAttempt = System.currentTimeMillis()
                    )
                }

                delay(delay)

                try {
                    // Intentar reconexión con timeout optimizado
                    val reconnectionResult = withTimeoutOrNull(
                        PerformanceOptimizer.getOptimizedDelay(connectionTimeout)
                    ) {
                        antManager.connect(deviceNumber)
                        // Esperar un poco para verificar conexión
                        delay(2000)
                        antManager.isConnectedToDevice(deviceNumber)
                    }

                    if (reconnectionResult == true) {
                        DebugLogger.logReconnectionEvent(deviceNumber, attempts, maxReconnectAttempts, delay, true)
                        updateConnectionState(deviceNumber) {
                            it.copy(
                                isConnected = true,
                                isReconnecting = false,
                                reconnectAttempts = 0,
                                lastError = null
                            )
                        }
                        return@launch
                    } else {
                        val error = if (reconnectionResult == null) "Timeout" else "Connection failed"
                        updateConnectionState(deviceNumber) {
                            it.copy(lastError = error)
                        }
                    }

                } catch (e: Exception) {
                    DebugLogger.logError("RECONNECTION", "Failed attempt $attempts for device $deviceNumber", e)
                    updateConnectionState(deviceNumber) {
                        it.copy(lastError = e.message)
                    }
                }
            }

            // Agotados todos los intentos
            DebugLogger.logConnectionEvent(deviceNumber, "RECONNECTION_FAILED", "Max attempts reached: $maxReconnectAttempts")
            updateConnectionState(deviceNumber) {
                it.copy(isReconnecting = false, lastError = "Max reconnection attempts reached")
            }
        }
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = (baseReconnectDelay * 2.0.pow(attempt - 1)).toLong()
        return min(exponentialDelay, maxReconnectDelay)
    }

    private fun updateConnectionState(deviceNumber: Int, update: (ConnectionState) -> ConnectionState) {
        val currentStates = _connectionStates.value.toMutableMap()
        val currentState = currentStates[deviceNumber] ?: ConnectionState(
            deviceNumber = deviceNumber,
            isConnected = false,
            isReconnecting = false,
            lastConnectionAttempt = 0L,
            reconnectAttempts = 0
        )
        currentStates[deviceNumber] = update(currentState)
        _connectionStates.value = currentStates
    }

    fun forceReconnect(deviceNumber: Int) {
        DebugLogger.logConnectionEvent(deviceNumber, "FORCE_RECONNECT")
        reconnectionJobs[deviceNumber]?.cancel()
        startReconnection(deviceNumber)
    }

    fun stopAllMonitoring() {
        monitoringJobs.values.forEach { it.cancel() }
        reconnectionJobs.values.forEach { it.cancel() }
        monitoringJobs.clear()
        reconnectionJobs.clear()
        _connectionStates.value = emptyMap()

        // Limpiar también el historial de heartbeat cuando se detiene el monitoreo
        PerformanceOptimizer.clearHeartbeatCaches()
        DebugLogger.logConnectionEvent(0, "MONITORING_STOPPED", "All monitoring stopped and heartbeat caches cleared")
    }
}
