package com.enderthor.kremote.viewmodel



import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.ReconnectionManagerSingleton
import com.enderthor.kremote.utils.ConnectionState
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber


class DebugViewModel(
    private val repository: RemoteRepository
) : ViewModel() {

    private val _isDebugEnabled = MutableStateFlow(DebugLogger.isEnabled())
    val isDebugEnabled: StateFlow<Boolean> = _isDebugEnabled.asStateFlow()

    private val _logContent = MutableStateFlow("")
    val logContent: StateFlow<String> = _logContent.asStateFlow()

    // Estados de conexión del ReconnectionManager
    private val reconnectionManagerStates = ReconnectionManagerSingleton.getInstance()?.connectionStates
        ?: MutableStateFlow<Map<Int, ConnectionState>>(emptyMap()).asStateFlow()

    // Dispositivos registrados desde el repositorio
    private val _registeredDevices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val registeredDevices: StateFlow<List<RemoteDevice>> = _registeredDevices.asStateFlow()

    // Combinar ambos estados para mostrar información completa
    val connectionStates = combine(
        reconnectionManagerStates,
        _registeredDevices
    ) { managerStates, devices ->
        // Si tenemos estados del ReconnectionManager, usarlos
        managerStates.ifEmpty {
            // Si no hay estados del manager, crear estados simulados basados en dispositivos registrados
            devices.associate { device ->
                val deviceId = device.antDeviceId ?: 0
                deviceId to ConnectionState(
                    deviceNumber = deviceId,
                    isConnected = true, // Asumimos conectado si el dispositivo está registrado
                    isReconnecting = false,
                    lastConnectionAttempt = System.currentTimeMillis(),
                    reconnectAttempts = 0,
                    lastError = null
                )
            }
        }
    }

    init {
        // Cargar dispositivos registrados
        viewModelScope.launch {
            repository.getDevices().collect { devices ->
                _registeredDevices.value = devices
                DebugLogger.logConnectionEvent(0, "DEVICES_LOADED", "Loaded ${devices.size} registered devices: ${devices.map { "${it.name}(#${it.antDeviceId})" }}", "DebugViewModel")
            }
        }

        refreshLog()

        // DIAGNÓSTICO EXTENDIDO: Verificar estado completo del singleton
        val reconnectionManager = ReconnectionManagerSingleton.getInstance()
        if (reconnectionManager != null) {
            DebugLogger.logConnectionEvent(0, "DEBUG_VIEWMODEL_INIT", "ReconnectionManager singleton found", "DebugViewModel")
            Timber.d("[DebugViewModel] ReconnectionManager singleton encontrado")

            // NUEVO: Verificar si hay estados de conexión activos
            viewModelScope.launch {
                reconnectionManager.connectionStates.collect { states ->
                    DebugLogger.logConnectionEvent(0, "CONNECTION_STATES_UPDATE", "States count: ${states.size}, devices: ${states.keys.toList()}", "DebugViewModel")
                    Timber.d("[DebugViewModel] Estados de conexión actualizados: ${states.size} dispositivos monitoreados")
                }
            }
        } else {
            DebugLogger.logConnectionEvent(0, "DEBUG_VIEWMODEL_INIT", "ReconnectionManager singleton is NULL - usando datos del repositorio", "DebugViewModel")
            Timber.w("[DebugViewModel] ReconnectionManager singleton es NULL - mostrando dispositivos registrados en su lugar")
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
