package com.enderthor.kremote.utils

import com.enderthor.kremote.ant.AntManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Gestor central del sistema de heartbeat inteligente
 * Coordina las 3 opciones: A (puntual), B (eventos reales), C (UI inteligente)
 */
object HeartbeatManager {

    // Lista de listeners para cambios de estado ANT+ (Opción B)
    private val connectionStateListeners = mutableMapOf<Int, MutableList<(Boolean) -> Unit>>()

    /**
     * Heartbeat para pantalla de debug
     * Llama a esto cuando el usuario entra en la pantalla de debug
     */
    suspend fun performDebugScreenCheck(deviceId: Int, antManager: AntManager): Boolean {
        return try {
            DebugLogger.logConnectionEvent(deviceId, "DEBUG_SCREEN_ENTERED", "Performing debug screen heartbeat")
            Timber.d("HeartbeatManager: Debug screen check for device $deviceId")

            val result = PerformanceOptimizer.performDebugScreenHeartbeat(deviceId, antManager)

            Timber.d("HeartbeatManager: Debug screen heartbeat result for device $deviceId: $result")
            result
        } catch (e: Exception) {
            DebugLogger.logError("DEBUG_HEARTBEAT", "Error in debug screen heartbeat", e)
            Timber.w(e, "HeartbeatManager: Error in debug screen heartbeat for device $deviceId")
            false
        }
    }

    /**
     * OPCIÓN C: Verificación inteligente para UI
     * Para cualquier pantalla que necesite conocer el estado de conexión
     */
    suspend fun performUICheck(deviceId: Int, antManager: AntManager, screenName: String): Boolean {
        return try {
            DebugLogger.logConnectionEvent(deviceId, "UI_CHECK_REQUESTED", "UI check requested by screen: $screenName")
            Timber.v("HeartbeatManager: UI check requested for device $deviceId by screen: $screenName")

            val result = PerformanceOptimizer.performUIVerification(deviceId, antManager, screenName)

            Timber.v("HeartbeatManager: UI check result for device $deviceId: $result")
            result
        } catch (e: Exception) {
            DebugLogger.logError("UI_VERIFICATION", "Error in UI verification for $screenName", e)
            Timber.w(e, "HeartbeatManager: Error in UI verification for device $deviceId (screen: $screenName)")
            false
        }
    }

    /**
     * OPCIÓN B: Registrar listener para eventos ANT+ reales
     * Para reaccionar instantáneamente a conexiones/desconexiones
     */
    fun registerConnectionListener(deviceId: Int, listener: (Boolean) -> Unit) {
        // Mantener registro local para gestión
        val listeners = connectionStateListeners.getOrPut(deviceId) { mutableListOf() }
        listeners.add(listener)

        DebugLogger.logConnectionEvent(deviceId, "CONNECTION_LISTENER_REGISTERED", "Registered connection state listener")
        Timber.d("HeartbeatManager: Registered connection listener for device $deviceId")
    }

    /**
     * Obtiene el último estado conocido sin hacer verificaciones activas
     * OPCIÓN B: Usa los eventos reales capturados
     */
    fun getLastKnownState(deviceId: Int): Boolean? {
        val state = PerformanceOptimizer.getLastKnownConnectionState(deviceId)
        Timber.v("HeartbeatManager: Last known state for device $deviceId: $state")
        return state
    }

    /**
     * Verifica si un dispositivo necesita verificación activa
     * OPCIÓN A: Basado en actividad reciente
     */
    fun shouldVerifyConnection(deviceId: Int): Boolean {
        val shouldVerify = PerformanceOptimizer.shouldVerifyConnection(deviceId)

        if (shouldVerify) {
            Timber.d("HeartbeatManager: Device $deviceId needs connection verification")
        } else {
            Timber.v("HeartbeatManager: Device $deviceId does not need verification (recent activity)")
        }

        return shouldVerify
    }

    /**
     * Limpia todas las cachés y listeners
     */
    fun cleanup() {
        connectionStateListeners.clear()
        PerformanceOptimizer.clearHeartbeatCaches()
        DebugLogger.logConnectionEvent(0, "HEARTBEAT_MANAGER_CLEANUP", "All heartbeat data cleared")
        Timber.i("HeartbeatManager: Cleaned up all caches and listeners")
    }

    /**
     * Configura limpieza periódica en un scope específico
     */
    fun setupPeriodicCleanup(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(300_000L) // 5 minutos
                try {
                    PerformanceOptimizer.cleanUIVerificationCache()
                    DebugLogger.logConnectionEvent(0, "PERIODIC_CLEANUP", "Periodic heartbeat cleanup completed")
                    Timber.v("HeartbeatManager: Periodic cleanup completed")
                } catch (e: Exception) {
                    DebugLogger.logError("PERIODIC_CLEANUP", "Error during periodic cleanup", e)
                    Timber.e(e, "HeartbeatManager: Error during periodic cleanup")
                }
            }
        }
    }
}
