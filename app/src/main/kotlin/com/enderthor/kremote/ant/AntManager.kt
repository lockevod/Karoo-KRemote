package com.enderthor.kremote.ant

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.controls.AntPlusGenericControllableDevicePcc
import com.dsi.ant.plugins.antplus.pcc.controls.defines.CommandStatus
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.DEFAULT_DOUBLE_TAP_TIMEOUT
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.data.getLabelString
import com.enderthor.kremote.data.minReconnectInterval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import timber.log.Timber

data class AntDeviceInfo(
    val name: String,
    val deviceNumber: Int
)

class AntManager(
    private val context: Context,
    private var commandCallback: (AntRemoteKey, PressType) -> Unit,
    private var doubleTapTimeout: Long = DEFAULT_DOUBLE_TAP_TIMEOUT
) {
    private var remotePcc: AntPlusGenericControllableDevicePcc? = null
    private var remoteReleaseHandle: PccReleaseHandle<AntPlusGenericControllableDevicePcc?>? = null


    private val _detectedDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val detectedDevices: StateFlow<List<AntDeviceInfo>> = _detectedDevices.asStateFlow()

    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    private var learningMode = false

    private var isConnecting = false
    private var lastConnectionAttempt = 0L

    private var doubleTapDetector: DoubleTapDetector? = null

    init {
        doubleTapDetector = DoubleTapDetector(doubleTapTimeout) { commandNumber, pressType ->
            val antCommand = AntRemoteKey.entries.find { it.gCommand == commandNumber }
            antCommand?.let {
                Timber.d("[ANT] Procesando comando: ${it.getLabelString(context)} (${if(pressType == PressType.DOUBLE) "DOBLE" else "SIMPLE"})")
                commandCallback.invoke(it, pressType)
            }
        }
    }

    private val mRemoteResultReceiver =
        AntPluginPcc.IPluginAccessResultReceiver<AntPlusGenericControllableDevicePcc> { result: AntPlusGenericControllableDevicePcc?,
                                                                                        resultCode: RequestAccessResult,
                                                                                        initialDeviceState: DeviceState ->
            when (resultCode) {
                RequestAccessResult.SUCCESS -> {
                    remotePcc = result
                    _isConnected = true
                    result?.let { pcc: AntPlusGenericControllableDevicePcc ->
                        val deviceInfo = AntDeviceInfo(
                            name = "ANT+ Remote #${pcc.antDeviceNumber}",
                            deviceNumber = pcc.antDeviceNumber
                        )

                        if (!_detectedDevices.value.any { it.deviceNumber == deviceInfo.deviceNumber }) {
                            _detectedDevices.value = _detectedDevices.value + deviceInfo
                        }
                    }
                    Timber.d("ANT+ remote connected successfully deviceNumber: ${result?.antDeviceNumber}")
                }

                RequestAccessResult.USER_CANCELLED -> {
                    _isConnected = false
                    Timber.w("User cancelled ANT+ remote connection")
                    disconnect()
                }

                RequestAccessResult.CHANNEL_NOT_AVAILABLE -> {
                    _isConnected = false
                    Timber.w("ANT+ channel not available")
                    disconnect()
                }

                RequestAccessResult.OTHER_FAILURE -> {
                    _isConnected = false
                    Timber.w("ANT+ connection failed")
                    disconnect()
                }

                RequestAccessResult.DEPENDENCY_NOT_INSTALLED -> {
                    _isConnected = false
                    Timber.w("ANT+ dependency not installed")
                    disconnect()
                }

                RequestAccessResult.DEVICE_ALREADY_IN_USE -> {
                    _isConnected = false
                    Timber.w("ANT+ device already in use")
                    disconnect()
                }

                RequestAccessResult.SEARCH_TIMEOUT -> {
                    _isConnected = false
                    Timber.w("ANT+ search timed out")
                    disconnect()
                }

                RequestAccessResult.ALREADY_SUBSCRIBED -> {
                    _isConnected = true
                    Timber.d("ANT+ already subscribed")
                }

                RequestAccessResult.BAD_PARAMS -> {
                    _isConnected = false
                    Timber.w("ANT+ bad parameters")
                    disconnect()
                }

                RequestAccessResult.ADAPTER_NOT_DETECTED -> {
                    _isConnected = false
                    Timber.w("ANT+ adapter not detected")
                    disconnect()
                }

                RequestAccessResult.UNRECOGNIZED -> {
                    _isConnected = false
                    Timber.w("ANT+ unrecognized result")
                    disconnect()
                }
            }

        }

    fun isConnectedToDevice(deviceNumber: Int): Boolean {
        return _isConnected && remotePcc?.antDeviceNumber == deviceNumber
    }
    fun setupCommandCallback(callback: (AntRemoteKey, PressType) -> Unit) {
        Timber.d("Configurando callback para comandos ANT+")
        this.commandCallback = callback
    }

    fun setLearningMode(enabled: Boolean) {
        learningMode = enabled
        Timber.d("Modo aprendizaje: $learningMode")
    }


    private val mRemoteCommand =
        AntPlusGenericControllableDevicePcc.IGenericCommandReceiver { _, _, _, _, _, commandNumber ->
            try {
                Timber.d("[ANT] Comando recibido: $commandNumber (Modo aprendizaje: $learningMode)")

                if (learningMode) {
                    val antCommand = AntRemoteKey.entries.find { it.gCommand == commandNumber }
                    antCommand?.let {
                        commandCallback.invoke(it, PressType.SINGLE)
                    }
                } else {
                    doubleTapDetector?.handleCommand(commandNumber)
                }
            } catch (e: Exception) {
                Timber.e(e, "[ANT] Error procesando comando")
            }
            CommandStatus.PASS
        }


    private val mRemoteDeviceStateChangeReceiver =
        AntPluginPcc.IDeviceStateChangeReceiver { newDeviceState: DeviceState ->
            when (newDeviceState) {
                DeviceState.DEAD -> {
                    _isConnected = false
                    Timber.d("ANT+ remote connection dead")
                    disconnect()
                }

                DeviceState.CLOSED -> {
                    _isConnected = false
                    Timber.d("ANT+ remote connection closed")
                }

                DeviceState.TRACKING -> {
                    _isConnected = true
                    Timber.d("ANT+ remote connected and tracking")
                }

                else -> Timber.d("ANT+ remote state changed: $newDeviceState")
            }
        }

    fun updateDoubleTapTimeout(timeout: Long) {
        this.doubleTapTimeout = timeout
        // Actualizar el doubleTapDetector existente o crear uno nuevo
        doubleTapDetector?.updateTimeout(timeout) ?: run {
            doubleTapDetector = DoubleTapDetector(timeout) { commandNumber, pressType ->
                val antCommand = AntRemoteKey.entries.find { it.gCommand == commandNumber }
                antCommand?.let {
                    Timber.d("[ANT] Procesando comando: ${it.getLabelString(context)} (${if(pressType == PressType.DOUBLE) "DOBLE" else "SIMPLE"})")
                    commandCallback.invoke(it, pressType)
                }
            }
        }
    }

    fun connect(deviceNumber: Int) {

        if (isConnecting) {
            Timber.d("[ANT] Ya hay un intento de conexión en curso")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastConnectionAttempt < minReconnectInterval) {
            Timber.d("[ANT] Intento de reconexión demasiado frecuente, ignorando")
            return
        }

        lastConnectionAttempt = now
        isConnecting = true


        runBlocking(Dispatchers.Main) {
            try {
                Timber.d("[ANT] Conectando a dispositivo #$deviceNumber (Modo aprendizaje: $learningMode)")


                val currentLearningMode = learningMode

                if (_isConnected && remotePcc?.antDeviceNumber != deviceNumber) {
                    disconnect()
                } else if (_isConnected && remotePcc?.antDeviceNumber == deviceNumber) {
                    Timber.d("[ANT] Ya conectado al dispositivo $deviceNumber")
                    isConnecting = false
                    return@runBlocking
                }

                learningMode = currentLearningMode

                remoteReleaseHandle = AntPlusGenericControllableDevicePcc.requestAccess(
                    context,
                    mRemoteResultReceiver,
                    mRemoteDeviceStateChangeReceiver,
                    mRemoteCommand,
                    deviceNumber
                )

                Timber.d("[ANT] Conexión solicitada al dispositivo #$deviceNumber")
            } catch (e: Exception) {
                _isConnected = false
                Timber.e(e, "[ANT] Error conectando al dispositivo")
            } finally {
                isConnecting = false
            }
        }
    }

    fun disconnect() {
        val wasLearning = learningMode
        Timber.d("Closing ANT+ remote handler (Modo aprendizaje: $learningMode)")
        remoteReleaseHandle?.close()
        remoteReleaseHandle = null
        remotePcc = null
        _isConnected = false

        if (wasLearning) {
            Timber.d("[ANT] Preservando modo aprendizaje: true")
            learningMode = true
        }
    }

    fun stopScan(disconnect: Boolean = false) {
        Timber.d("Stopping ANT+ device search (disconnect=$disconnect)")
        try {
            _detectedDevices.value = emptyList()
            if (disconnect) {
                disconnect()
                _isConnected = false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping ANT+ device search")
            throw e
        }
    }

   fun startDeviceSearch() {
        Timber.d("Starting ANT+ device search")
        try {

            runBlocking(Dispatchers.Main) {

                disconnect()

                remoteReleaseHandle = AntPlusGenericControllableDevicePcc.requestAccess(
                    context,
                    mRemoteResultReceiver,
                    mRemoteDeviceStateChangeReceiver,
                    mRemoteCommand,
                    0 // DeviceNumber 0 para buscar cualquier dispositivo
                )

                Timber.d("ANT+ device search started successfully remoteReleaseHandle: $remoteReleaseHandle channel: $remotePcc")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting ANT+ device search")
            throw e
        }
    }


    fun cleanup() {
        try {
            remoteReleaseHandle?.close()
            remoteReleaseHandle = null
            Timber.d("ANT+ cleanup completado")
        } catch (e: Exception) {
            Timber.e(e, "Error en cleanup de ANT+")
        }
    }
}