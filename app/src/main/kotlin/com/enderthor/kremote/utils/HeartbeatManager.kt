package com.enderthor.kremote.utils

import com.enderthor.kremote.ant.AntManager
import timber.log.Timber

/**
 * Central manager for intelligent heartbeat system
 * Coordinates the 3 options: A (punctual), B (real events), C (intelligent UI)
 */
object HeartbeatManager {

    /**
     * Heartbeat for debug screen
     * Call this when user enters the debug screen
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
     * OPTION C: Intelligent verification for UI
     * For any screen that needs to know the connection status
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
     * OPTION B: Register listener for real ANT+ events
     * To react instantly to connections/disconnections.
     * Delegates to PerformanceOptimizer — which is the same module that fires
     * `notifyAntConnectionStateChanged` from the ANT+ binder callback.
     * Antes había un mapa local aquí que jamás se conectaba con el dispatch,
     * con lo que el callback de reconexión nunca llegaba a saltar.
     */
    fun registerConnectionListener(deviceId: Int, listener: (Boolean) -> Unit) {
        PerformanceOptimizer.addAntEventListener(deviceId, listener)
        DebugLogger.logConnectionEvent(deviceId, "CONNECTION_LISTENER_REGISTERED", "Registered connection state listener")
        Timber.d("HeartbeatManager: Registered connection listener for device $deviceId")
    }

    /**
     * Elimina los listeners de conexión de un dispositivo. startMonitoring lo llama
     * antes de registrar uno nuevo para no acumular callbacks duplicados.
     */
    fun unregisterConnectionListeners(deviceId: Int) {
        PerformanceOptimizer.removeAntEventListeners(deviceId)
        Timber.d("HeartbeatManager: Unregistered connection listeners for device $deviceId")
    }

    /**
     * Gets the last known state without performing active verifications
     * OPTION B: Uses real captured events
     */
    fun getLastKnownState(deviceId: Int): Boolean? {
        val state = PerformanceOptimizer.getLastKnownConnectionState(deviceId)
        Timber.v("HeartbeatManager: Last known state for device $deviceId: $state")
        return state
    }

    /**
     * Checks if a device needs active verification
     * OPTION A: Based on recent activity
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
     * Cleans all caches and listeners
     */
    fun cleanup() {
        PerformanceOptimizer.clearHeartbeatCaches()
        DebugLogger.logConnectionEvent(0, "HEARTBEAT_MANAGER_CLEANUP", "All heartbeat data cleared")
        Timber.i("HeartbeatManager: Cleaned up all caches and listeners")
    }

    // setupPeriodicCleanup() eliminado: despertaba cada 5 min (288 veces al día) para podar
    // `uiVerificationRequests`, un mapa acotado por nº de dispositivos — en la práctica UNA
    // entrada ("<id>_reconnection_manager") que se reescribe sola en cada verificación y
    // nunca crece. Coste sin beneficio. La poda que sí sirve (fin de ruta) sigue viva en
    // PerformanceOptimizer.setRidingState, y stopAllMonitoring limpia todas las cachés.
}
