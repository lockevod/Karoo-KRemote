package com.enderthor.kremote.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Optimizador de rendimiento para reducir el uso de CPU y memoria
 */
object PerformanceOptimizer {

    private val _isOptimizationEnabled = MutableStateFlow(true)
    val isOptimizationEnabled: StateFlow<Boolean> = _isOptimizationEnabled.asStateFlow()

    // Pool de corrutinas reutilizables para operaciones frecuentes
    @OptIn(ExperimentalCoroutinesApi::class)
    private val quickOperationDispatcher = Dispatchers.Default.limitedParallelism(2)

    // Caché para evitar recrear objetos frecuentemente
    private val commandCache = ConcurrentHashMap<String, Any>()
    private val connectionStateCache = ConcurrentHashMap<Int, Long>()

    // OPCIÓN A: Sistema de heartbeat puntual (simple y eficiente)
    private val deviceActivityCache = ConcurrentHashMap<Int, Long>()

    // OPCIÓN B: Sistema de detección de eventos ANT+ reales
    private val antEventListeners = ConcurrentHashMap<Int, MutableList<(Boolean) -> Unit>>()
    private val lastKnownConnectionState = ConcurrentHashMap<Int, Boolean>()

    // OPCIÓN C: Verificación inteligente solo para UI
    private val uiVerificationRequests = ConcurrentHashMap<String, Long>()

    // NUEVO: Detección automática de estado de "riding" desde KremoteExtension
    private val _isRiding = MutableStateFlow(false)
    // Exponer isRiding como propiedad pública para que se pueda usar desde otras clases


    // Configuración simple del heartbeat puntual
    private const val NO_ACTIVITY_THRESHOLD_MS = 120000L // 2 minutos sin actividad
    private const val HIGH_INACTIVITY_CHECK_INTERVAL_MS = 600000L // 10 minutos para inactividad alta
    private const val DEBUG_SCREEN_CHECK_TIMEOUT_MS = 5000L // 5 segundos timeout para check de pantalla debug

    // NUEVO: Configuración diferente según estado de riding
    private const val RIDING_HEARTBEAT_TIMEOUT_MS = 3000L // 3 segundos timeout cuando está en riding (CRÍTICO)
    private const val NORMAL_HEARTBEAT_TIMEOUT_MS = 5000L // 5 segundos timeout cuando NO está en riding
    private const val UI_VERIFICATION_CACHE_MS = 30000L // 30 segundos de caché para verificaciones de UI

    fun setOptimizationEnabled(enabled: Boolean) {
        _isOptimizationEnabled.value = enabled
        if (!enabled) {
            clearCaches()
        }
    }

    /**
     * Ejecuta una operación con throttling para evitar spam
     */
    suspend fun throttledExecution(
        key: String,
        minIntervalMs: Long = 100L,
        operation: suspend () -> Unit
    ) {
        if (!_isOptimizationEnabled.value) {
            operation()
            return
        }

        val now = System.currentTimeMillis()
        val lastExecution = connectionStateCache[key.hashCode()] ?: 0L

        if (now - lastExecution >= minIntervalMs) {
            connectionStateCache[key.hashCode()] = now
            withContext(quickOperationDispatcher) {
                operation()
            }
        }
    }

    /**
     * Obtiene un objeto del caché o lo crea si no existe
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getCachedOrCreate(key: String, factory: () -> T): T {
        if (!_isOptimizationEnabled.value) {
            return factory()
        }

        return commandCache.getOrPut(key) { factory() } as T
    }

    /**
     * Limpia las cachés para liberar memoria
     */
    fun clearCaches() {
        commandCache.clear()
        connectionStateCache.clear()
        deviceActivityCache.clear()
    }

    /**
     * Optimiza el delay dinámicamente basado en la carga del sistema
     */
    fun getOptimizedDelay(baseDelay: Long, loadFactor: Float = 1.0f): Long {
        if (!_isOptimizationEnabled.value) {
            return baseDelay
        }

        // Ajustar el delay basado en la carga del sistema
        val adjustedDelay = (baseDelay * loadFactor).toLong()
        return min(adjustedDelay, baseDelay * 2) // Máximo 2x el delay base
    }

    /**
     * Obtiene estadísticas de uso del optimizador
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "isEnabled" to _isOptimizationEnabled.value,
            "commandCacheSize" to commandCache.size,
            "connectionStateCacheSize" to connectionStateCache.size,
            "quickOperationDispatcher" to quickOperationDispatcher.toString()
        )
    }

    /**
     * Obtiene estadísticas formateadas para mostrar en UI
     */
    fun getFormattedStats(
        optimizationsStatus: String,
        enabledStatus: String,
        disabledStatus: String,
        commandCacheLabel: String,
        connectionCacheLabel: String,
        coroutinePoolLabel: String
    ): String {
        val stats = getStats()
        return buildString {
            appendLine(optimizationsStatus.format(if (stats["isEnabled"] as Boolean) enabledStatus else disabledStatus))
            appendLine(commandCacheLabel.format(stats["commandCacheSize"]))
            appendLine(connectionCacheLabel.format(stats["connectionStateCacheSize"]))
            appendLine(coroutinePoolLabel)
        }
    }

    // === OPCIÓN A: HEARTBEAT ACTIVO PUNTUAL (SIMPLE Y EFICIENTE) ===

    /**
     * Registra actividad de un dispositivo (comando recibido)
     */
    fun recordDeviceActivity(deviceNumber: Int) {
        deviceActivityCache[deviceNumber] = System.currentTimeMillis()
        DebugLogger.logConnectionEvent(deviceNumber, "ACTIVITY_RECORDED", "Device activity recorded", "PerformanceOptimizer")
    }

    /**
     * Registra que un dispositivo acaba de reconectarse
     */
    fun recordDeviceReconnection(deviceNumber: Int) {
        recordDeviceActivity(deviceNumber) // También registrar como actividad
        DebugLogger.logConnectionEvent(deviceNumber, "RECONNECTION_RECORDED", "Device reconnection recorded", "PerformanceOptimizer")
    }

    /**
     * Calcula el intervalo óptimo para verificación de conexión
     */
    fun getOptimalVerificationInterval(deviceNumber: Int): Long {
        if (!_isOptimizationEnabled.value) return 10000L // Default 10s

        val now = System.currentTimeMillis()
        val lastActivity = deviceActivityCache[deviceNumber] ?: 0L
        val timeSinceActivity = now - lastActivity

        // Si está en riding, intervalos menos agresivos pero más inteligentes
        if (_isRiding.value) {
            return when {
                // Primera verificación después de detectar riding: 30 segundos
                // (para confirmar que la conexión funciona al iniciar)
                timeSinceActivity < 30000L -> 30000L

                // Verificaciones de mantenimiento durante riding: cada 5 minutos
                // (mucho menos agresivo que antes)
                else -> 300000L // 5 minutos
            }
        } else {
            // Fuera de riding, intervalos muy relajados
            return when {
                // Sin riding, actividad reciente: 10 minutos
                timeSinceActivity < NO_ACTIVITY_THRESHOLD_MS -> 600000L // 10 minutos

                // Sin riding, sin actividad: 15 minutos
                timeSinceActivity > HIGH_INACTIVITY_CHECK_INTERVAL_MS -> 900000L // 15 minutos

                // Sin riding, intervalo estándar: 10 minutos
                else -> 600000L // 10 minutos
            }
        }
    }

    /**
     * Limpia las cachés del sistema de heartbeat
     */
    fun clearHeartbeatCaches() {
        deviceActivityCache.clear()

        // Opción B
        antEventListeners.clear()
        lastKnownConnectionState.clear()

        // Opción C
        uiVerificationRequests.clear()

        DebugLogger.logConnectionEvent(0, "ALL_HEARTBEAT_CACHES_CLEARED", "All heartbeat system caches cleared", "PerformanceOptimizer")
    }

    /**
     * Determina si un dispositivo necesita verificación de conexión
     * OPCIÓN A: Solo verifica en momentos muy específicos
     */
    fun shouldVerifyConnection(deviceNumber: Int): Boolean {
        if (!_isOptimizationEnabled.value) return true

        val now = System.currentTimeMillis()
        val lastActivity = deviceActivityCache[deviceNumber] ?: 0L

        // Si no hay registro de actividad, verificar
        if (lastActivity == 0L) return true

        val timeSinceActivity = now - lastActivity

        // Solo verificar si han pasado más de 2 minutos sin actividad
        return timeSinceActivity > NO_ACTIVITY_THRESHOLD_MS
    }

    /**
     * OPCIÓN A: Heartbeat puntual para pantalla de debug
     * Solo se ejecuta cuando se abre la pantalla de debug
     */
    suspend fun performDebugScreenHeartbeat(
        deviceNumber: Int,
        antManager: com.enderthor.kremote.ant.AntManager
    ): Boolean {


        // Usar timeout adaptativo según estado de riding
        val timeout = if (_isRiding.value) RIDING_HEARTBEAT_TIMEOUT_MS else NORMAL_HEARTBEAT_TIMEOUT_MS

        return withTimeoutOrNull(timeout) {
            try {
                DebugLogger.logConnectionEvent(
                    deviceNumber,
                    "DEBUG_HEARTBEAT_START",
                    "Debug screen heartbeat check started (timeout: ${timeout}ms, riding: ${_isRiding.value})",
                    "PerformanceOptimizer"
                )

                val isConnected = antManager.isConnectedToDevice(deviceNumber)

                DebugLogger.logConnectionEvent(
                    deviceNumber,
                    "DEBUG_HEARTBEAT_RESULT",
                    "Debug screen heartbeat result: $isConnected (timeout: ${timeout}ms)",
                    "PerformanceOptimizer"
                )

                if (isConnected) {
                    recordDeviceActivity(deviceNumber)
                }

                isConnected
            } catch (e: Exception) {
                DebugLogger.logError(
                    "DEBUG_HEARTBEAT",
                    "Debug screen heartbeat failed for device $deviceNumber (timeout: ${timeout}ms)",
                    e,
                    "PerformanceOptimizer"
                )
                false
            }
        } ?: run {
            DebugLogger.logConnectionEvent(
                deviceNumber,
                "DEBUG_HEARTBEAT_TIMEOUT",
                "Debug screen heartbeat timed out after ${timeout}ms (riding: ${_isRiding.value})",
                "PerformanceOptimizer"
            )
            false
        }
    }

    /**
     * OPCIÓN B: Notifica un cambio real de estado de conexión ANT+
     * Llama a esto cuando el stack ANT+ reporta eventos reales de conexión/desconexión
     */
    fun notifyAntConnectionStateChanged(deviceNumber: Int, isConnected: Boolean) {
        val previousState = lastKnownConnectionState[deviceNumber]

        // Solo proceder si hay un cambio real de estado
        if (previousState != isConnected) {
            lastKnownConnectionState[deviceNumber] = isConnected

            DebugLogger.logConnectionEvent(
                deviceNumber,
                "ANT_REAL_EVENT",
                "Real ANT+ event: ${if (isConnected) "CONNECTED" else "DISCONNECTED"} (was: $previousState)",
                "PerformanceOptimizer"
            )

            // Actualizar actividad si se conectó
            if (isConnected) {
                recordDeviceActivity(deviceNumber)
            }

            // Notificar a todos los listeners registrados
            antEventListeners[deviceNumber]?.forEach { listener ->
                try {
                    listener(isConnected)
                } catch (e: Exception) {
                    DebugLogger.logError(
                        "ANT_EVENT_LISTENER",
                        "Error notifying ANT+ event listener",
                        e,
                        "PerformanceOptimizer"
                    )
                }
            }
        }
    }

    /**
     * Obtiene el último estado conocido de conexión ANT+ sin hacer verificaciones activas
     */
    fun getLastKnownConnectionState(deviceNumber: Int): Boolean? {
        return lastKnownConnectionState[deviceNumber]
    }

    // === OPCIÓN C: VERIFICACIÓN INTELIGENTE SOLO PARA UI (MUY EFICIENTE) ===

    /**
     * OPCIÓN C: Verificación inteligente solo cuando la UI la necesita
     * Usa caché para evitar verificaciones repetidas innecesarias
     */
    suspend fun performUIVerification(
        deviceNumber: Int,
        antManager: com.enderthor.kremote.ant.AntManager,
        requestId: String = "ui_request"
    ): Boolean {
        val now = System.currentTimeMillis()
        val cacheKey = "${deviceNumber}_$requestId"
        val lastVerification = uiVerificationRequests[cacheKey] ?: 0L

        // Si ya verificamos recientemente, usar resultado cacheado
        if (now - lastVerification < UI_VERIFICATION_CACHE_MS) {
            val cachedResult = lastKnownConnectionState[deviceNumber] ?: false
            DebugLogger.logConnectionEvent(
                deviceNumber,
                "UI_VERIFICATION_CACHED",
                "Using cached result: $cachedResult (${now - lastVerification}ms ago)",
                "PerformanceOptimizer"
            )
            return cachedResult
        }

        // Hacer verificación real
        uiVerificationRequests[cacheKey] = now

        return withTimeoutOrNull(DEBUG_SCREEN_CHECK_TIMEOUT_MS) {
            try {
                DebugLogger.logConnectionEvent(
                    deviceNumber,
                    "UI_VERIFICATION_START",
                    "UI requested verification (requestId: $requestId)",
                    "PerformanceOptimizer"
                )

                val isConnected = antManager.isConnectedToDevice(deviceNumber)

                DebugLogger.logConnectionEvent(
                    deviceNumber,
                    "UI_VERIFICATION_RESULT",
                    "UI verification result: $isConnected",
                    "PerformanceOptimizer"
                )

                // Actualizar estado conocido y actividad
                notifyAntConnectionStateChanged(deviceNumber, isConnected)
                if (isConnected) {
                    recordDeviceActivity(deviceNumber)
                }

                isConnected
            } catch (e: Exception) {
                DebugLogger.logError(
                    "UI_VERIFICATION",
                    "UI verification failed for device $deviceNumber",
                    e,
                    "PerformanceOptimizer"
                )
                false
            }
        } ?: run {
            DebugLogger.logConnectionEvent(
                deviceNumber,
                "UI_VERIFICATION_TIMEOUT",
                "UI verification timed out",
                "PerformanceOptimizer"
            )
            false
        }
    }

    /**
     * Limpia caché de verificaciones de UI (llamar periódicamente)
     */
    fun cleanUIVerificationCache() {
        val now = System.currentTimeMillis()
        val iterator = uiVerificationRequests.iterator()
        var removed = 0

        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > UI_VERIFICATION_CACHE_MS * 2) {
                iterator.remove()
                removed++
            }
        }

        if (removed > 0) {
            DebugLogger.logConnectionEvent(
                0,
                "UI_CACHE_CLEANUP",
                "Cleaned $removed expired UI verification entries",
                "PerformanceOptimizer"
            )
        }
    }

    /**
     * NUEVO: Establece el estado de riding desde KremoteExtension
     * Esta función es llamada automáticamente cuando cambia el estado del ride
     */
    fun setRidingState(isRiding: Boolean) {
        val wasRiding = _isRiding.value
        _isRiding.value = isRiding

        DebugLogger.logConnectionEvent(
            deviceNumber = 0,
            event = "RIDING_STATE_CHANGED",
            details = "Riding state changed: $wasRiding -> $isRiding. Heartbeat mode: ${if (isRiding) "CRITICAL" else "NORMAL"}",
            source = "PerformanceOptimizer"
        )

        // Si dejamos de hacer riding, limpiar algunas cachés para optimizar memoria
        if (wasRiding && !isRiding) {
            cleanUIVerificationCache()
            DebugLogger.logConnectionEvent(
                deviceNumber = 0,
                event = "RIDING_STOP_CLEANUP",
                details = "Cleaned caches after stopping ride",
                source = "PerformanceOptimizer"
            )
        }
    }
}
