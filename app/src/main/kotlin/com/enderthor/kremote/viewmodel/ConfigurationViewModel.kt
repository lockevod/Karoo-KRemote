package com.enderthor.kremote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.AntRemoteKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import timber.log.Timber

class ConfigurationViewModel(
    private val repository: RemoteRepository
) : ViewModel() {

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    @Suppress("unused")
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private val _activeDevice = MutableStateFlow<RemoteDevice?>(null)
    val activeDevice: StateFlow<RemoteDevice?> = _activeDevice.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getDevices().collect {
                _devices.value = it
            }
        }

        viewModelScope.launch {
            repository.getActiveDevice().collect {
                _activeDevice.value = it
            }
        }
    }

    fun assignKeyCodeToCommand(deviceId: String, command: AntRemoteKey, karooKey: KarooKey?) {
        viewModelScope.launch {
            try {
                repository.assignKeyCodeToCommand(deviceId, command, karooKey)
            } catch (e: Exception) {
                Timber.e(e, "Error asignando KeyCode al comando")
                _errorMessage.value = "Error al asignar el comando: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}