package com.enderthor.kremote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteRepository
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
                repository.currentConfig.collect { config ->
                    Timber.d("ConfigurationViewModel recibió nueva config: $config")
                    _activeDevice.value = config.devices.find { it.isActive }
                    Timber.d("Dispositivo activo actualizado: ${_activeDevice.value}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error cargando configuración")
                _errorMessage.value = "Error cargando configuración: ${e.message}"
            }
        }
    }
    fun updateKeyMapping(deviceId: String, button: String, key: KarooKey) {
        viewModelScope.launch {
            try {
                val device = repository.getDeviceById(deviceId) ?: run {
                    _errorMessage.value = "Device not found"
                    return@launch
                }

                val currentMappings = device.keyMappings
                val newMappings = when (button) {
                    "left" -> currentMappings.copy(remoteleft = key)
                    "right" -> currentMappings.copy(remoteright = key)
                    "up" -> currentMappings.copy(remoteup = key)
                    else -> {
                        _errorMessage.value = "Unknown button: $button"
                        return@launch
                    }
                }

                repository.updateDeviceMapping(deviceId, newMappings)
                Timber.d("Key mapping updated for device $deviceId: $button -> ${key.label}")
            } catch (e: Exception) {
                Timber.e(e, "Error updating key mapping")
                _errorMessage.value = "Error updating configuration: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}