// viewmodel/ConfigurationViewModel.kt
package com.enderthor.kremote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteSettings
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber

class ConfigurationViewModel(
    private val repository: RemoteRepository
) : ViewModel() {
    private val _activeDevice = MutableStateFlow<RemoteDevice?>(null)
    val activeDevice: StateFlow<RemoteDevice?> = _activeDevice.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                // Usar getActiveDevice() en lugar de acceder a currentConfig
                repository.getActiveDevice().collect { device ->
                    _activeDevice.value = device
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading configuration")
                _errorMessage.value = "Error al cargar la configuración: ${e.message}"
            }
        }
    }

    fun updateKeyMapping(deviceId: String, button: String, key: KarooKey) {
        viewModelScope.launch {
            try {
                val device = _activeDevice.value ?: run {
                    _errorMessage.value = "No hay dispositivo activo"
                    return@launch
                }

                val currentMappings = device.keyMappings
                val newMappings = when (button) {
                    "left" -> currentMappings.copy(remoteleft = key)
                    "right" -> currentMappings.copy(remoteright = key)
                    "up" -> currentMappings.copy(remoteup = key)
                    else -> {
                        _errorMessage.value = "Botón no reconocido: $button"
                        return@launch
                    }
                }

                repository.updateDeviceMapping(deviceId, newMappings)
                Timber.d("Key mapping updated for device $deviceId: $button -> ${key.label}")
            } catch (e: Exception) {
                Timber.e(e, "Error updating key mapping")
                _errorMessage.value = "Error al actualizar la configuración: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}