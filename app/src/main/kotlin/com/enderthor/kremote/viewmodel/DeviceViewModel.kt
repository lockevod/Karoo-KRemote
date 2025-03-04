package com.enderthor.kremote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kremote.ant.AntDeviceInfo
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.data.DeviceMessage
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import android.content.Context
import java.util.UUID

class DeviceViewModel(
    private val antManager: AntManager,
    private val repository: RemoteRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<RemoteDevice?>(null)
    val selectedDevice: StateFlow<RemoteDevice?> = _selectedDevice.asStateFlow()

    private val _availableAntDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val availableAntDevices: StateFlow<List<AntDeviceInfo>> = _availableAntDevices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _message = MutableStateFlow<DeviceMessage?>(null)
    val message: StateFlow<DeviceMessage?> = _message.asStateFlow()

    private val _learnedCommands = MutableStateFlow<List<AntRemoteKey>>(emptyList())
    val learnedCommands: StateFlow<List<AntRemoteKey>> = _learnedCommands.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getDevices().collect {
                _devices.value = it
            }
        }

        antManager.setupCommandCallback { command, pressType ->
            if (scanning.value) {
                onCommandDetected(command, pressType)
            }
        }
    }

    private fun getString(resId: Int): String = appContext.getString(resId)
    private fun getString(resId: Int, vararg formatArgs: Any): String = appContext.getString(resId, *formatArgs)


    fun clearSelectedDevice() {
        _selectedDevice.value = null
        _learnedCommands.value = emptyList()
    }

    fun startDeviceScan() {
        _scanning.value = true
        _availableAntDevices.value = emptyList()

        scanJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    antManager.startDeviceSearch()
                }

                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 30000) {
                    _availableAntDevices.value = antManager.detectedDevices.value
                    delay(1000)
                }
            } catch (e: CancellationException) {
                Timber.d("Scan job cancelled $e")
            } catch (e: Exception) {
                Timber.e(e, "Error during device scan")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            } finally {
                _scanning.value = false
                try {
                    antManager.stopScan()
                } catch (e: Exception) {
                    Timber.e(e, "Error stopping scan")
                }
            }
        }
    }

    fun onDeviceConfigureClick(device: RemoteDevice) {
        _selectedDevice.value = device


        viewModelScope.launch {
            try {
                device.antDeviceId?.let { deviceId ->
                    withContext(Dispatchers.IO) {
                        antManager.connect(deviceId)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error connecting to ANT+ device for configuration")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun activateDevice(device: RemoteDevice) {
        viewModelScope.launch {
            try {
                repository.setActiveDevice(device.id)
            } catch (e: Exception) {
                Timber.e(e, "Error activating device")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                repository.removeDevice(deviceId)
                _message.value = DeviceMessage.Success(getString(R.string.device_deleted))
            } catch (e: Exception) {
                Timber.e(e, "Error removing device")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun onNewAntDeviceSelected(deviceInfo: AntDeviceInfo) {
        _scanning.value = false
        scanJob?.cancel()

        viewModelScope.launch {
            try {
                val deviceId = UUID.randomUUID().toString()
                val newDevice = RemoteDevice(
                    id = deviceId,
                    name = deviceInfo.name,
                    type = RemoteType.ANT,
                    antDeviceId = deviceInfo.deviceNumber,
                    macAddress = deviceInfo.deviceNumber.toString()
                )

                repository.addDevice(newDevice)
                _message.value = DeviceMessage.Success(getString(R.string.remote_registered_successfully))

                repository.setActiveDevice(deviceId)
            } catch (e: Exception) {
                Timber.e(e, "Error adding new ANT+ device")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun startLearning() {
        _scanning.value = true
        _learnedCommands.value = emptyList()
        selectedDevice.value?.let { device ->
            try {
                device.antDeviceId?.let { deviceId ->
                    antManager.setLearningMode(true)
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            antManager.connect(deviceId)
                        } catch (e: Exception) {
                            Timber.e(e, "Error connecting to ANT+ device for learning")
                            withContext(Dispatchers.Main) {
                                _scanning.value = false
                                _message.value = DeviceMessage.Error(getString(R.string.error))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _scanning.value = false
                Timber.e(e, "Error starting learning mode")
                _message.value = DeviceMessage.Error(getString(R.string.error))
            }
        }
    }

    fun stopLearning() {
        _scanning.value = false
        antManager.setLearningMode(false)

        saveLearnedCommands()
    }

    fun restartLearning() {
        stopLearning()
        _learnedCommands.value = emptyList()
        startLearning()
    }

    private fun onCommandDetected(command: AntRemoteKey, pressType: PressType = PressType.SINGLE) {

        if (!_learnedCommands.value.contains(command)) {
            _learnedCommands.value = _learnedCommands.value + command


            selectedDevice.value?.let { device ->
                viewModelScope.launch {
                    try {

                        repository.updateLearnedCommand(device.id, command, pressType)
                        _message.value = DeviceMessage.Success(
                            getString(R.string.command_learned, command.label)
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Error saving learned command")
                    }
                }
            }
        }
    }

    private fun saveLearnedCommands() {
        selectedDevice.value?.let { device ->
            viewModelScope.launch {
                try {
                    for (command in _learnedCommands.value) {
                        repository.updateLearnedCommand(device.id, command)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error saving learned commands")
                    _message.value = DeviceMessage.Error(getString(R.string.error))
                }
            }
        }
    }

    fun clearAllLearnedCommands() {
        selectedDevice.value?.let { device ->
            viewModelScope.launch {
                try {
                    repository.clearLearnedCommands(device.id)
                    _learnedCommands.value = emptyList()
                    _message.value = DeviceMessage.Success(getString(R.string.all_commands_cleared))
                } catch (e: Exception) {
                    Timber.e(e, "Error clearing learned commands")
                    _message.value = DeviceMessage.Error(getString(R.string.error))
                }
            }
        }
    }
}