package com.enderthor.kremote.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.hal.BuzzerClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import timber.log.Timber

class ConfigurationViewModel(
    private val repository: RemoteRepository,
    private val appContext: Context
) : ViewModel() {

    // Fix: _devices / devices / _activeDevice eliminados — nada los consumía fuera del ViewModel.
    // La UI recibe los dispositivos directamente de DeviceViewModel (TabLayout → ConfigurationScreen).

    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _onlyWhileRiding = MutableStateFlow(true)
    val onlyWhileRiding: StateFlow<Boolean> = _onlyWhileRiding.asStateFlow()

    private val _forcedScreenOn = MutableStateFlow(false)
    val forcedScreenOn: StateFlow<Boolean> = _forcedScreenOn.asStateFlow()

    private val _bypassMute = MutableStateFlow(false)
    val bypassMute: StateFlow<Boolean> = _bypassMute.asStateFlow()

    // Resultado del botón "Probar zumbador" (BeepResult.name o mensaje de bind); null = sin probar.
    private val _buzzerTestResult = MutableStateFlow<String?>(null)
    val buzzerTestResult: StateFlow<String?> = _buzzerTestResult.asStateFlow()

    private val _buzzerTesting = MutableStateFlow(false)
    val buzzerTesting: StateFlow<Boolean> = _buzzerTesting.asStateFlow()


    init {
        viewModelScope.launch {
            repository.currentConfig.collect { config ->
                _onlyWhileRiding.value = config.globalSettings.onlyWhileRiding
                _forcedScreenOn.value = config.globalSettings.isForcedScreenOn
                _bypassMute.value = config.globalSettings.bypassMute
            }
        }
    }

    fun assignKeyCodeToCommand(deviceId: String, command: AntRemoteKey, karooKey: KarooKey?, pressType: PressType) {
        viewModelScope.launch {
            try {
                repository.assignKeyCodeToCommand(deviceId, command, karooKey, pressType)
            } catch (e: Exception) {
                Timber.e(e, "Error asignando KeyCode al comando")
                _errorMessage.value = "Error assigning keycode: ${e.message}"
            }
        }
    }

    fun updateOnlyWhileRiding(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateGlobalSetting { it.copy(onlyWhileRiding = enabled) }
            } catch (e: Exception) {
                Timber.e(e, "Error updating onlyWhileRiding configuration")
                _errorMessage.value = "Error updating configuration: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun updateDoubleTapEnabled(deviceId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateDeviceProperty(deviceId) { device ->
                    device.copy(enabledDoubleTap = enabled)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error updating enabledDoubleTap configuration")
                _errorMessage.value = "Error updating configuration: ${e.message}"
            }
        }
    }

    fun updateDoubleTapTimeout(deviceId: String, timeout: Long) {
        viewModelScope.launch {
            try {
                repository.updateDeviceProperty(deviceId) { device ->
                    device.copy(doubleTapTimeout = timeout)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error updating doubleTapTimeout configuration")
                _errorMessage.value = "Error updating configuration: ${e.message}"
            }
        }
    }

    fun updateForcedScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateGlobalSetting { it.copy(isForcedScreenOn = enabled) }
            } catch (e: Exception) {
                Timber.e(e, "Error updating isForcedScreenOn configuration")
                _errorMessage.value = "Error updating configuration: ${e.message}"
            }
        }
    }

    fun updateBypassMute(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateGlobalSetting { it.copy(bypassMute = enabled) }
            } catch (e: Exception) {
                Timber.e(e, "Error updating bypassMute configuration")
                _errorMessage.value = "Error updating configuration: ${e.message}"
            }
        }
    }

    /**
     * Bindea su PROPIO BuzzerClient (independiente del de la extensión, que puede no estar
     * vivo cuando se abre la app), dispara un beep de prueba por el HAL y publica el
     * resultado para que el rider pueda diagnosticar tras un OTA sin logcat.
     */
    fun testBuzzer() {
        if (_buzzerTesting.value) return
        viewModelScope.launch {
            _buzzerTesting.value = true
            _buzzerTestResult.value = null
            val client = BuzzerClient(appContext)
            try {
                val bindMsg = client.connect()
                // El bind es asíncrono: esperamos a que llegue el binder (máx ~2s).
                var waited = 0
                while (!client.isReady() && waited < 2000) {
                    delay(100)
                    waited += 100
                }
                if (!client.isReady()) {
                    _buzzerTestResult.value = "BIND_FAILED: $bindMsg"
                    return@launch
                }
                client.beep(BuzzerClient.TEST_TONES)
                _buzzerTestResult.value = client.lastResult.name
                delay(600) // deja sonar el tono antes de soltar el bind
            } catch (e: CancellationException) {
                // Fix: no atrapar CancellationException — relanzar para que el scope se cancele limpiamente
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error testing buzzer")
                _buzzerTestResult.value = "ERROR: ${e.message}"
            } finally {
                client.disconnect()
                _buzzerTesting.value = false
            }
        }
    }
}