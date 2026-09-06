package com.enderthor.kremote.utils

import com.enderthor.kremote.ant.AntManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
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

    // ConcurrentHashMap: estos mapas se tocan desde el bucle de monitoreo, desde el
    // antEventListener (binder ANT+ → scope.launch IO) y desde forceReconnect (UI),
    // potencialmente en paralelo. Con HashMap plano el patrón cancel-and-replace
    // podía dejar coroutines huérfanas spammeando requestAccess() al stack ANT+.
    private val reconnectionJobs = ConcurrentHashMap<Int, Job>()
    private val monitoringJobs = ConcurrentHashMap<Int, Job>()

    // Acceso público al AntManager para heartbeat
    fun getAntManager(): AntManager = antManager

    // Configuración mejorada con PerformanceOptimizer
    private val baseReconnectDelay = 2000L
    private val maxReconnectDelay = 30000L
    private val maxReconnectAttempts = 15
    // Una vez agotados los intentos con backoff exponencial corto, no abandonamos:
    // pasamos a un backoff largo y fijo para no dejar la conexión muerta hasta
    // reiniciar la app. 60s es suficientemente espaciado para no spamear el stack
    // ANT+ y suficientemente frecuente para recuperarse cuando vuelva la señal.
    private val longBackoffDelay = 60000L
    // Fuera de ruta el mando no se está usando: el Karoo puede estar en la mochila o cargando.
    // Un mando apagado o sin pila mantenía requestAccess() cada 60 s indefinidamente (~1440
    // despertares de radio ANT+ al día). x10 menos, sin dejar la conexión muerta.
    private val offRideLongBackoffDelay = 600000L
    // La espera larga se duerme a tramos para poder reaccionar si arranca una ruta a mitad:
    // si no, el rider podía esperar hasta 10 min a que su mando volviera.
    private val backoffSliceMs = 60000L
    private val connectionCheckInterval = 10000L
    private val connectionTimeout = 15000L

    // Limpieza periódica vía HeartbeatManager; se guarda el Job para cancelarlo en
    // stopAllMonitoring (solo se llama al descartar esta instancia desde el singleton).
    private val periodicCleanupJob = HeartbeatManager.setupPeriodicCleanup(scope)

    fun startMonitoring(deviceNumber: Int) {
        DebugLogger.logConnectionEvent(deviceNumber, "START_MONITORING", "Iniciando monitoreo para dispositivo #$deviceNumber", "ReconnectionManager")
        Timber.d("[ReconnectionManager] 🔍 Iniciando monitoreo para dispositivo #$deviceNumber")

        // Cancelar monitoring anterior si existe (atómico — si dos hilos entran a
        // startMonitoring para el mismo deviceNumber, el remove devuelve el job solo
        // una vez al ganador, evitando huérfanos sin referencia en el mapa).
        monitoringJobs.remove(deviceNumber)?.cancel()
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

        // Quitar cualquier listener previo de este dispositivo antes de registrar el
        // nuevo: si startMonitoring se llama otra vez (p. ej. reinicio del servicio),
        // evita acumular listeners y disparar reconexiones duplicadas.
        HeartbeatManager.unregisterConnectionListeners(deviceNumber)
        HeartbeatManager.registerConnectionListener(deviceNumber, antEventListener)

        val initialState = ConnectionState(
            deviceNumber = deviceNumber,
            isConnected = false,
            isReconnecting = false,
            lastConnectionAttempt = 0L,
            reconnectAttempts = 0
        )

        updateConnectionState(deviceNumber) { initialState }
        DebugLogger.logConnectionEvent(deviceNumber, "INITIAL_STATE_SET", "Estado inicial establecido: $initialState", "ReconnectionManager")

        val newMonitoringJob = scope.launch {
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
                                // Si el sync revela una caída y no estamos reconectando, lanzar
                                // reconexión inmediatamente. Antes se actualizaba el flag pero
                                // nunca se reconectaba: en la siguiente vuelta currentState.isConnected
                                // ya era false y la rama del `if (shouldVerify)` no podía detectar
                                // la transición → la conexión quedaba muerta hasta reiniciar la app.
                                if (!lastKnownState && !currentState.isReconnecting) {
                                    Timber.w("[ReconnectionManager] 🔴 lastKnownState=disconnected for #$deviceNumber — kicking reconnection")
                                    startReconnection(deviceNumber)
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
        // Publicación atómica: si entre el remove de arriba y este put otro hilo metió
        // su propio job, lo cancelamos aquí en lugar de dejarlo huérfano.
        monitoringJobs.put(deviceNumber, newMonitoringJob)?.cancel()

        DebugLogger.logConnectionEvent(deviceNumber, "MONITORING_JOB_CREATED", "Job de monitoreo creado y almacenado", "ReconnectionManager")
        Timber.d("[ReconnectionManager] ✅ Monitoreo configurado correctamente para dispositivo #$deviceNumber")
    }

    private fun startReconnection(deviceNumber: Int) {
        // Construir el nuevo job y publicarlo atómicamente. `put` devuelve el job
        // anterior (si lo había) → lo cancelamos sin riesgo de huérfano. Si dos
        // hilos entran aquí simultáneamente, ambos crean un job y compiten en put:
        // el último gana, el otro se cancela vía el valor previo devuelto.
        val newJob = scope.launch {
            var attempts = 0

            updateConnectionState(deviceNumber) {
                it.copy(isReconnecting = true, isConnected = false)
            }

            // Fase 1: backoff exponencial corto (15 intentos, hasta 30s entre cada uno).
            // Fase 2: si no conecta, NO abandonamos — pasamos a backoff largo fijo de 60s
            // indefinidamente. El job sigue vivo y mantiene `isReconnecting=true`, así que
            // el bucle de monitoreo no necesita re-detectar transiciones para volver a
            // intentarlo (que es lo que antes nos dejaba la conexión muerta hasta reinicio).
            while (isActive) {
                attempts++
                val isLongBackoff = attempts > maxReconnectAttempts

                // La fase 2 es indefinida, así que es la que decide el coste en batería:
                // fuera de ruta la relajamos x10. La fase 1 (backoff exponencial corto) NO se
                // toca — si estás configurando en casa quieres que conecte ya.
                val delay = if (isLongBackoff) {
                    if (PerformanceOptimizer.isRiding) longBackoffDelay else offRideLongBackoffDelay
                } else {
                    PerformanceOptimizer.getOptimizedDelay(
                        calculateBackoffDelay(attempts),
                        loadFactor = if (attempts > 5) 1.5f else 1.0f // Aumentar delay si hay muchos fallos
                    )
                }

                DebugLogger.logReconnectionEvent(deviceNumber, attempts, maxReconnectAttempts, delay, false)
                if (isLongBackoff && attempts == maxReconnectAttempts + 1) {
                    DebugLogger.logConnectionEvent(
                        deviceNumber,
                        "RECONNECTION_LONG_BACKOFF",
                        "Switched to long backoff (${longBackoffDelay}ms) after $maxReconnectAttempts short attempts",
                        "ReconnectionManager"
                    )
                    Timber.w("[ReconnectionManager] ⏳ Device #$deviceNumber entering long backoff after $maxReconnectAttempts attempts")
                }

                updateConnectionState(deviceNumber) {
                    it.copy(
                        reconnectAttempts = attempts,
                        lastConnectionAttempt = System.currentTimeMillis()
                    )
                }

                awaitBackoff(delay)

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
        }
        reconnectionJobs.put(deviceNumber, newJob)?.cancel()
    }

    /**
     * Duerme [totalMs] en tramos de [backoffSliceMs], cortando en cuanto arranca una ruta.
     *
     * Sin esto, relajar el backoff fuera de ruta tendría un efecto secundario malo: el rider
     * empieza a rodar en mitad de una espera de 10 minutos y se queda sin mando hasta que
     * se agota. Cortando en el primer tramo, el peor caso al empezar la ruta es ~60 s.
     */
    private suspend fun awaitBackoff(totalMs: Long) {
        var waited = 0L
        while (waited < totalMs) {
            val slice = min(backoffSliceMs, totalMs - waited)
            delay(slice)
            waited += slice
            if (PerformanceOptimizer.isRiding) return
        }
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = (baseReconnectDelay * 2.0.pow(attempt - 1)).toLong()
        return min(exponentialDelay, maxReconnectDelay)
    }

    private fun updateConnectionState(deviceNumber: Int, transform: (ConnectionState) -> ConnectionState) {
        // CAS atómico: varios productores (loop de monitoreo, listener ANT+, job de
        // reconexión) pueden tocar el mapa a la vez; update{} reintenta hasta ganar
        // el compareAndSet, evitando que un read→copy→write pierda actualizaciones.
        _connectionStates.update { currentStates ->
            val currentState = currentStates[deviceNumber] ?: ConnectionState(
                deviceNumber = deviceNumber,
                isConnected = false,
                isReconnecting = false,
                lastConnectionAttempt = 0L,
                reconnectAttempts = 0
            )
            currentStates + (deviceNumber to transform(currentState))
        }
    }

    fun forceReconnect(deviceNumber: Int) {
        DebugLogger.logConnectionEvent(deviceNumber, "FORCE_RECONNECT")
        reconnectionJobs[deviceNumber]?.cancel()
        startReconnection(deviceNumber)
    }

    fun stopAllMonitoring() {
        periodicCleanupJob.cancel()
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
