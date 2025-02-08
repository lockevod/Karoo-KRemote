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
import timber.log.Timber
import java.util.EnumSet

class AntManager(
    private val context: Context,
    private val commandCallback: (GenericCommandNumber) -> Unit
) {
    private var remotePcc: AntPlusGenericControllableDevicePcc? = null
    private var remoteReleaseHandle: PccReleaseHandle<AntPlusGenericControllableDevicePcc?>? = null
    private var lastCommandTime: Long = 0
    private val commandDebounceTime = 300L // 300ms debounce time

    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    private val mRemoteResultReceiver =
        AntPluginPcc.IPluginAccessResultReceiver<AntPlusGenericControllableDevicePcc> {
                result: AntPlusGenericControllableDevicePcc?,
                resultCode: RequestAccessResult,
                initialDeviceState: DeviceState ->

            when (resultCode) {
                RequestAccessResult.SUCCESS -> {
                    remotePcc = result
                    _isConnected = true
                    Timber.d("ANT+ remote connected successfully")
                }
                else -> {
                    _isConnected = false
                    Timber.w("ANT+ remote connection failed: $resultCode")
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

    fun connect() {
        Timber.d("Initializing ANT+ remote connection")
        if (remoteReleaseHandle == null) {
            remoteReleaseHandle = AntPlusGenericControllableDevicePcc.requestAccess(
                context,
                mRemoteResultReceiver,
                mRemoteDeviceStateChangeReceiver,
                mRemoteCommand,
                0
            )
        }
    }

    fun disconnect() {
        Timber.d("Closing ANT+ remote handler")
        remoteReleaseHandle?.close()
        remoteReleaseHandle = null
        remotePcc = null
        _isConnected = false
    }

    private fun handleCommand(
        estTimestamp: Long,
        commandNumber: GenericCommandNumber
    ): CommandStatus {
        // Debounce check
        if (estTimestamp - lastCommandTime < commandDebounceTime) {
            return CommandStatus.PASS
        }
        lastCommandTime = estTimestamp

        when (commandNumber) {
            GenericCommandNumber.MENU_DOWN,
            GenericCommandNumber.LAP,
            GenericCommandNumber.UNRECOGNIZED -> {
                commandCallback(commandNumber)
            }
            else -> {
                Timber.d("Unhandled ANT+ command: $commandNumber")
            }
        }

        return CommandStatus.PASS
    }

    fun cleanup() {
        disconnect()
    }
}