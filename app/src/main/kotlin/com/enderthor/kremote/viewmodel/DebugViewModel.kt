package com.enderthor.kremote.viewmodel


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.ReconnectionManagerSingleton
import com.enderthor.kremote.utils.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber


class DebugViewModel : ViewModel() {

    private val _isDebugEnabled = MutableStateFlow(DebugLogger.isEnabled())
    val isDebugEnabled: StateFlow<Boolean> = _isDebugEnabled.asStateFlow()

    private val _logContent = MutableStateFlow("")
    val logContent: StateFlow<String> = _logContent.asStateFlow()

    // Usar el singleton en lugar de recibir ReconnectionManager como parámetro
    val connectionStates = ReconnectionManagerSingleton.getInstance()?.connectionStates
        ?: MutableStateFlow<Map<Int, ConnectionState>>(emptyMap()).asStateFlow()

    init {
        refreshLog()

        // DIAGNÓSTICO: Verificar si el singleton está disponible
        val reconnectionManager = ReconnectionManagerSingleton.getInstance()
        if (reconnectionManager != null) {
            DebugLogger.logConnectionEvent(0, "DEBUG_VIEWMODEL_INIT", "ReconnectionManager singleton found", "DebugViewModel")
            Timber.d("[DebugViewModel] ReconnectionManager singleton encontrado")
        } else {
            DebugLogger.logConnectionEvent(0, "DEBUG_VIEWMODEL_INIT", "ReconnectionManager singleton is NULL", "DebugViewModel")
            Timber.w("[DebugViewModel] ReconnectionManager singleton es NULL - no se podrán mostrar estados de conexión")
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
}
