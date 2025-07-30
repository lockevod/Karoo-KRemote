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

    // NUEVO: Sistema de heartbeat inteligente
    private val deviceActivityCache = ConcurrentHashMap<Int, Long>()
    private val deviceReconnectionHistory = ConcurrentHashMap<Int, MutableList<Long>>()

    // Configuración del heartbeat adaptativo
    private const val MAX_INACTIVE_TIME_MS = 45000L // 45 segundos sin actividad
    private const val RECENT_RECONNECTION_WINDOW_MS = 120000L // 2 minutos después de reconectar
    private const val MAX_HISTORY_ENTRIES = 10

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
        // También limpiar cachés del sistema de heartbeat
        clearHeartbeatCaches()
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
     * Ejecuta limpieza periódica de memoria
     */
    fun schedulePeriodicCleanup(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(300_000) // 5 minutos
                if (commandCache.size > 100) {
                    clearCaches()
                    DebugLogger.logConnectionEvent(0, "CACHE_CLEANUP", "Periodic cache cleanup performed")
                }
            }
        }
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

    // === NUEVO SISTEMA DE HEARTBEAT INTELIGENTE ===

    /**
     * Registra actividad de un dispositivo (comando recibido)
     */
    fun recordDeviceActivity(deviceNumber: Int) {
        deviceActivityCache[deviceNumber] = System.currentTimeMillis()
        DebugLogger.logConnectionEvent(deviceNumber, "ACTIVITY_RECORDED", "Heartbeat updated", "PerformanceOptimizer")
    }

    /**
     * Registra que un dispositivo acaba de reconectarse
     */
    fun recordDeviceReconnection(deviceNumber: Int) {
        val now = System.currentTimeMillis()
        val history = deviceReconnectionHistory.getOrPut(deviceNumber) { mutableListOf() }

        // Añadir nueva reconexión y limpiar entradas antiguas
        history.add(now)
        history.removeAll { now - it > RECENT_RECONNECTION_WINDOW_MS * 5 } // Mantener historial de 10 minutos

        // Limitar tamaño del historial
        if (history.size > MAX_HISTORY_ENTRIES) {
            history.removeAt(0)
        }

        DebugLogger.logConnectionEvent(deviceNumber, "RECONNECTION_RECORDED", "History size: ${history.size}", "PerformanceOptimizer")
    }

    /**
     * Determina si un dispositivo necesita verificación de conexión
     * Basado en actividad reciente y historial de reconexiones
     */
    fun shouldVerifyConnection(deviceNumber: Int): Boolean {
        if (!_isOptimizationEnabled.value) return true

        val now = System.currentTimeMillis()
        val lastActivity = deviceActivityCache[deviceNumber] ?: 0L
        val timeSinceActivity = now - lastActivity

        // Si no hay registro de actividad, verificar
        if (lastActivity == 0L) return true

        // Si ha pasado mucho tiempo sin actividad, verificar
        if (timeSinceActivity > MAX_INACTIVE_TIME_MS) {
            DebugLogger.logConnectionEvent(deviceNumber, "VERIFICATION_NEEDED", "No activity for ${timeSinceActivity}ms", "PerformanceOptimizer")
            return true
        }

        // Si acabamos de reconectar recientemente, verificar más seguido
        val recentReconnections = deviceReconnectionHistory[deviceNumber]?.count {
            now - it < RECENT_RECONNECTION_WINDOW_MS
        } ?: 0

        if (recentReconnections > 0) {
            // Verificar cada 15 segundos si hubo reconexiones recientes
            val shouldVerify = timeSinceActivity > 15000L
            if (shouldVerify) {
                DebugLogger.logConnectionEvent(deviceNumber, "VERIFICATION_NEEDED", "Recent reconnections: $recentReconnections", "PerformanceOptimizer")
            }
            return shouldVerify
        }

        return false
    }

    /**
     * Calcula el intervalo óptimo para verificación de conexión
     */
    fun getOptimalVerificationInterval(deviceNumber: Int): Long {
        if (!_isOptimizationEnabled.value) return 10000L // Default 10s

        val now = System.currentTimeMillis()
        val lastActivity = deviceActivityCache[deviceNumber] ?: 0L
        val recentReconnections = deviceReconnectionHistory[deviceNumber]?.count {
            now - it < RECENT_RECONNECTION_WINDOW_MS
        } ?: 0

        return when {
            // Si hubo reconexiones recientes, verificar más seguido
            recentReconnections > 0 -> 15000L // 15 segundos

            // Si hay actividad reciente, verificar menos seguido
            (now - lastActivity) < 30000L -> 60000L // 1 minuto

            // Si no hay actividad, intervalo medio
            else -> 30000L // 30 segundos
        }
    }

    /**
     * Limpia cachés del sistema de heartbeat
     */
    fun clearHeartbeatCaches() {
        deviceActivityCache.clear()
        deviceReconnectionHistory.clear()
        DebugLogger.logConnectionEvent(0, "HEARTBEAT_CACHE_CLEARED", "All device activity history cleared", "PerformanceOptimizer")
    }
}
