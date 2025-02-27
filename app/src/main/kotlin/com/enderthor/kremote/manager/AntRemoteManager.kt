package com.enderthor.kremote.manager

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag
import com.dsi.ant.plugins.antplus.pcc.controls.AntPlusGenericControllableDevicePcc
import com.dsi.ant.plugins.antplus.pcc.controls.AntPlusGenericControllableDevicePcc.IGenericCommandReceiver
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import com.dsi.ant.plugins.antplus.pcc.controls.defines.CommandStatus
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc.IDeviceStateChangeReceiver
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc.IPluginAccessResultReceiver
import com.enderthor.kremote.extension.KremoteExtension
import com.enderthor.kremote.extension.streamSettings
import com.enderthor.kremote.data.RemoteSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.EnumSet

class AntRemoteManager(
    private val kremoteExtension: KremoteExtension,
    private val context: Context,
    private val karooActionManager: KarooActionManager
) {

    private var remotePcc: AntPlusGenericControllableDevicePcc? = null
    private var remoteReleaseHandle: PccReleaseHandle<AntPlusGenericControllableDevicePcc?>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentSettings: RemoteSettings? = null

    init {
        scope.launch {
            context.streamSettings().collectLatest { settings ->
                currentSettings = settings
            }
        }
    }

    private val mRemoteResultReceiver =
        IPluginAccessResultReceiver { result: AntPlusGenericControllableDevicePcc?, resultCode: RequestAccessResult?, initialDeviceState: DeviceState? ->
            if (resultCode === RequestAccessResult.SUCCESS) {
                remotePcc = result
                Timber.d("ANT+ device connected")
            } else {
                Timber.e("ANT+ device connection failed: $resultCode")
                reconnectAntRemote()
            }
        }

    private val mRemoteDeviceStateChangeReceiver =
        IDeviceStateChangeReceiver { newDeviceState: DeviceState? ->
            Timber.d("ANT+ device state changed: $newDeviceState")
            if (newDeviceState === DeviceState.DEAD) {
                Timber.d("ANT+ device dead, reconnecting")
                closeAntHandler()
                reconnectAntRemote()
            }
        }

    private val mRemoteCommand =
        IGenericCommandReceiver { estTimestamp: Long, eventFlags: EnumSet<EventFlag?>?, serialNumber: Int, manufacturerID: Int, sequenceNumber: Int, commandNumber: GenericCommandNumber? ->
            onNewGenericCommand(
                estTimestamp,
                eventFlags,
                serialNumber,
                manufacturerID,
                sequenceNumber,
                commandNumber
            )
        }

    fun startAntRemote() {
        Timber.d("Starting ANT+ remote")
        connectAntRemote()
    }

    private fun connectAntRemote() {
        if (remoteReleaseHandle == null) {
            remoteReleaseHandle = AntPlusGenericControllableDevicePcc.requestAccess(
                kremoteExtension,
                mRemoteResultReceiver,
                mRemoteDeviceStateChangeReceiver,
                mRemoteCommand,
                0
            )
        }
    }

    private fun reconnectAntRemote() {
        CoroutineScope(Dispatchers.IO).launch {
            Timber.d("Attempting to reconnect to ANT+ device")
            // Delay before attempting to reconnect
            delay(5000)
            connectAntRemote()
        }
    }

    fun closeAntHandler() {
        Timber.d("Closing ANT+ handler")
        remoteReleaseHandle?.close()
        remoteReleaseHandle = null
    }

    private fun onNewGenericCommand(
        estTimestamp: Long,
        eventFlags: EnumSet<EventFlag?>?,
        serialNumber: Int,
        manufacturerID: Int,
        sequenceNumber: Int,
        commandNumber: GenericCommandNumber?
    ): CommandStatus {
        currentSettings?.let { settings ->
            if (kremoteExtension.onRideApp || !settings.onlyWhileRiding) {
                scope.launch {
                    when (commandNumber) {
                        GenericCommandNumber.MENU_DOWN -> {
                            Timber.d("IN ANTPLUS RIGHT ${settings.remoteright}")
                            karooActionManager.executeAction(settings.remoteright.action)
                        }
                        GenericCommandNumber.LAP -> {
                            Timber.d("IN ANTPLUS BACK ${settings.remoteleft}")
                            karooActionManager.executeAction(settings.remoteleft.action)
                        }
                        GenericCommandNumber.UNRECOGNIZED -> {
                            Timber.d("IN ANTPLUS MAP ${settings.remoteup}")
                            karooActionManager.executeAction(settings.remoteup.action)
                        }
                        else -> {
                            Timber.d("IN ANTPLUS UNHANDLED COMMAND")
                        }
                    }
                }
            }
        }
        return CommandStatus.PASS
    }

    /*
    private fun onNewGenericCommand(
        estTimestamp: Long,
        eventFlags: EnumSet<EventFlag?>?,
        serialNumber: Int,
        manufacturerID: Int,
        sequenceNumber: Int,
        commandNumber: GenericCommandNumber?
    ): CommandStatus {
        CoroutineScope(Dispatchers.IO).launch {
            context.streamSettings().collectLatest { settings ->
                if (kremoteExtension.onRideApp || !settings.onlyWhileRiding) {
                    when (commandNumber) {
                        GenericCommandNumber.MENU_DOWN -> {
                            Timber.d("IN ANTPLUS RIGHT ${settings.remoteright.action}")
                            karooActionManager.executeAction(settings.remoteright.action)
                        }
                        GenericCommandNumber.LAP -> {
                            Timber.d("IN ANTPLUS BACK ${settings.remoteleft.action}")
                            karooActionManager.executeAction(settings.remoteleft.action)
                        }
                        GenericCommandNumber.UNRECOGNIZED -> {
                            Timber.d("IN ANTPLUS MAP ${settings.remoteup.action}")
                            karooActionManager.executeAction(settings.remoteup.action)
                        }
                        else -> {
                            Timber.d("IN ANTPLUS UNHANDLED COMMAND")
                        }
                    }
                } else Timber.d("On Ride not active")
            }
        }
        return CommandStatus.PASS
    }

    */

}