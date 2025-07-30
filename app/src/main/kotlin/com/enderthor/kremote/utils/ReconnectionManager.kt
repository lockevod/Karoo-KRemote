package com.enderthor.kremote.utils

import com.enderthor.kremote.ant.AntManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Configuración mejorada con PerformanceOptimizer
    private val baseReconnectDelay = 2000L
    private val maxReconnectDelay = 30000L
    private val maxReconnectAttempts = 15
    private val connectionCheckInterval = 10000L
    private val connectionTimeout = 15000L

    init {
        // Inicializar limpieza periódica de PerformanceOptimizer
        PerformanceOptimizer.schedulePeriodicCleanup(scope)
    }

    fun startMonitoring(deviceNumber: Int) {
        DebugLogger.logConnectionEvent(deviceNumber, "START_MONITORING")

        // Cancelar monitoring anterior si existe
        monitoringJobs[deviceNumber]?.cancel()

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

        monitoringJobs[deviceNumber] = scope.launch {
            while (isActive) {
                try {
                    // NUEVO: Usar sistema de heartbeat inteligente
                    val shouldVerify = PerformanceOptimizer.shouldVerifyConnection(deviceNumber)

                    if (shouldVerify) {
                        val isConnected = antManager.isConnectedToDevice(deviceNumber)
                        val currentState = _connectionStates.value[deviceNumber]

                        if (currentState != null) {
                            if (!isConnected && currentState.isConnected && !currentState.isReconnecting) {
                                // Conexión perdida, iniciar reconexión
                                DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_LOST", "Starting reconnection process")
                                startReconnection(deviceNumber)
                            } else if (isConnected && !currentState.isConnected) {
                                // Conexión recuperada
                                DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_RECOVERED")
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
                    }

                    // NUEVO: Usar intervalo adaptativo en lugar de delay fijo
                    val optimalInterval = PerformanceOptimizer.getOptimalVerificationInterval(deviceNumber)
                    delay(optimalInterval)

                } catch (e: Exception) {
                    DebugLogger.logError("MONITORING", "Error monitoring device $deviceNumber", e)
                    delay(connectionCheckInterval)
                }
            }
        }
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
