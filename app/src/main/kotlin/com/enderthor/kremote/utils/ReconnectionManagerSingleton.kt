package com.enderthor.kremote.utils

import com.enderthor.kremote.ant.AntManager
import kotlinx.coroutines.CoroutineScope

/**
 * Singleton  ReconnectionManager
 * between ConnectionService and DebugViewModel
 */
object ReconnectionManagerSingleton {
    private var _instance: ReconnectionManager? = null

    fun initialize(antManager: AntManager, scope: CoroutineScope): ReconnectionManager {
        _instance?.stopAllMonitoring() // Clean up any existing instance before creating a new one
        _instance = ReconnectionManager(antManager, scope)
        DebugLogger.logConnectionEvent(0, "RECONNECTION_MANAGER_INITIALIZED", "Singleton instance created")
        return _instance!!
    }

    fun getInstance(): ReconnectionManager? = _instance

    fun destroy() {
        _instance?.stopAllMonitoring()
        _instance = null
        DebugLogger.logConnectionEvent(0, "RECONNECTION_MANAGER_DESTROYED", "Singleton instance destroyed")
    }
}
