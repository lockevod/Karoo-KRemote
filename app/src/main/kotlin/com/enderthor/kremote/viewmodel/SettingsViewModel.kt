package com.enderthor.kremote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.data.GlobalSettings
import com.enderthor.kremote.data.RemoteRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber

class SettingsViewModel(
    private val repository: RemoteRepository
) : ViewModel() {
    private val _settings = MutableStateFlow(GlobalSettings())
    val settings: StateFlow<GlobalSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                // Usar getGlobalSettings() en lugar de acceder a currentConfig
                repository.getGlobalSettings().collect { globalSettings ->
                    _settings.value = globalSettings
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading settings")
            }
        }
    }

    fun updateAutoConnectOnStart(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val updatedSettings = _settings.value.copy(autoConnectOnStart = enabled)
                repository.updateGlobalSettings(updatedSettings)
            } catch (e: Exception) {
                Timber.e(e, "Error updating autoConnectOnStart setting")
            }
        }
    }

    fun updateAutoReconnect(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val updatedSettings = _settings.value.copy(autoReconnect = enabled)
                repository.updateGlobalSettings(updatedSettings)
            } catch (e: Exception) {
                Timber.e(e, "Error updating autoReconnect setting")
            }
        }
    }

    fun updateReconnectAttempts(attempts: Int) {
        viewModelScope.launch {
            try {
                val updatedSettings = _settings.value.copy(reconnectAttempts = attempts)
                repository.updateGlobalSettings(updatedSettings)
            } catch (e: Exception) {
                Timber.e(e, "Error updating reconnectAttempts setting")
            }
        }
    }

    fun updateReconnectDelay(delayMs: Long) {
        viewModelScope.launch {
            try {
                val updatedSettings = _settings.value.copy(reconnectDelayMs = delayMs)
                repository.updateGlobalSettings(updatedSettings)
            } catch (e: Exception) {
                Timber.e(e, "Error updating reconnectDelay setting")
            }
        }
    }
}