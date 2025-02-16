package com.enderthor.kremote.ant

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.controls.AntPlusGenericControllableDevicePcc
import com.dsi.ant.plugins.antplus.pcc.controls.defines.CommandStatus
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.EnumSet

data class AntDeviceInfo(
    val name: String,
    val deviceNumber: Int
)

class AntManager(
    private val context: Context,
    private val commandCallback: (GenericCommandNumber) -> Unit
) {
    private var remotePcc: AntPlusGenericControllableDevicePcc? = null
    private var remoteReleaseHandle: PccReleaseHandle<AntPlusGenericControllableDevicePcc?>? = null
    private var lastCommandTime: Long = 0
    private val commandDebounceTime = 300L // 300ms debounce time

    private val _detectedDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val detectedDevices: StateFlow<List<AntDeviceInfo>> = _detectedDevices.asStateFlow()

    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected


    private fun handleCommand(
        estTimestamp: Long,
        commandNumber: GenericCommandNumber
    ): CommandStatus {
        Timber.d("[ANT] === INICIO handleCommand ===")
        Timber.d("[ANT] Callback hash: ${System.identityHashCode(commandCallback)}")
        Timber.d("[ANT] Comando recibido: $commandNumber")

        if (estTimestamp - lastCommandTime < commandDebounceTime) {
            Timber.d("[ANT] Comando ignorado por debounce")
            return CommandStatus.PASS
        }
        lastCommandTime = estTimestamp

        when (commandNumber) {
            GenericCommandNumber.MENU_DOWN,
            GenericCommandNumber.LAP,
            GenericCommandNumber.UNRECOGNIZED -> {
                try {
                    Timber.d("[ANT] Pre-ejecutar callback")
                    commandCallback.invoke(commandNumber)
                    Timber.d("[ANT] Post-ejecutar callback")
                } catch (e: Exception) {
                    Timber.e(e, "[ANT] Error ejecutando callback: ${e.message}")
                    e.printStackTrace()
                }
            }
            else -> Timber.d("[ANT] Comando no manejado: $commandNumber")
        }

        Timber.d("[ANT] === FIN handleCommand ===")
        return CommandStatus.PASS
    }


    private val mRemoteResultReceiver =
        AntPluginPcc.IPluginAccessResultReceiver<AntPlusGenericControllableDevicePcc> {
                result: AntPlusGenericControllableDevicePcc?,
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

    private val mRemoteCommand =
        AntPlusGenericControllableDevicePcc.IGenericCommandReceiver {
                estTimestamp: Long,
                eventFlags: EnumSet<EventFlag>,
                serialNumber: Int,
                manufacturerID: Int,
                sequenceNumber: Int,
                commandNumber: GenericCommandNumber ->

            handleCommand(estTimestamp, commandNumber)
        }


    suspend fun connect(deviceNumber: Int) {
        withContext(Dispatchers.Main) {
            try {
                Timber.d("Conectando a dispositivo ANT+ $deviceNumber")

                // Primero desconectamos si hay una conexión existente
                disconnect()

                // Iniciamos la nueva conexión con el orden correcto de parámetros
                remoteReleaseHandle = AntPlusGenericControllableDevicePcc.requestAccess(
                    context,
                    mRemoteResultReceiver,
                    mRemoteDeviceStateChangeReceiver,
                    mRemoteCommand,
                    deviceNumber // Número específico del dispositivo
                )

                Timber.d("Solicitud de conexión ANT+ enviada para dispositivo #$deviceNumber")
            } catch (e: Exception) {
                _isConnected = false
                Timber.e(e, "Error conectando a dispositivo ANT+")
                throw e
            }
        }
    }





    fun disconnect() {
        Timber.d("Closing ANT+ remote handler")
        remoteReleaseHandle?.close()
        remoteReleaseHandle = null
        remotePcc = null
        _isConnected = false
    }

    fun stopScan() {
        Timber.d("Stopping ANT+ device search")
        try {
            // Cerramos la búsqueda actual desconectando el dispositivo
            _detectedDevices.value = emptyList()
            disconnect()
            _isConnected = false
        } catch (e: Exception) {
            Timber.e(e, "Error stopping ANT+ device search")
            throw e
        }
    }

    fun startDeviceSearch() {
        Timber.d("Starting ANT+ device search")
        try {
            // Si ya hay una conexión ANT+, la desconectamos primero
            disconnect()

            // Iniciamos la búsqueda de dispositivos ANT+
            remoteReleaseHandle = AntPlusGenericControllableDevicePcc.requestAccess(
                context,
                mRemoteResultReceiver,
                mRemoteDeviceStateChangeReceiver,
                mRemoteCommand,
                0 // DeviceNumber 0 para buscar cualquier dispositivo
            )

            Timber.d("ANT+ device search started successfully remoteReleaseHandle: $remoteReleaseHandle channel: $remotePcc")

        } catch (e: Exception) {
            Timber.e(e, "Error starting ANT+ device search")
            throw e
        }
    }



/*
    private fun handleCommand(
        estTimestamp: Long,
        commandNumber: GenericCommandNumber
    ): CommandStatus {
        Timber.d("[ANT] === INICIO handleCommand ===")
        Timber.d("[ANT] Thread actual: ${Thread.currentThread().name}")
        Timber.d("[ANT] Comando recibido: $commandNumber")

        if (estTimestamp - lastCommandTime < commandDebounceTime) {
            Timber.d("[ANT] Comando ignorado por debounce")
            return CommandStatus.PASS
        }
        lastCommandTime = estTimestamp

        when (commandNumber) {
            GenericCommandNumber.MENU_DOWN,
            GenericCommandNumber.LAP,
            GenericCommandNumber.UNRECOGNIZED -> {
                try {
                    Timber.d("[ANT] Pre-ejecutar callback")
                    commandCallback.invoke(commandNumber)
                    Timber.d("[ANT] Post-ejecutar callback")
                } catch (e: Exception) {
                    Timber.e(e, "[ANT] Error ejecutando callback: ${e.message}")
                    e.printStackTrace()
                }
            }
            else -> Timber.d("[ANT] Comando no manejado: $commandNumber")
        }

        Timber.d("[ANT] === FIN handleCommand ===")
        return CommandStatus.PASS
    }
*/


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