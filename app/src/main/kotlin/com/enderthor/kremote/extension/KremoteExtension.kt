package com.enderthor.kremote.extension


import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.PerformHardwareAction


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



import timber.log.Timber
import java.util.EnumSet


class KremoteExtension : KarooExtension("kremote", "1.0") {

    lateinit var karooSystem: KarooSystemService


    /// Datafields

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        Timber.d("oncreate")
        remote_key()

    }

    //ANT Remote Code
    private var remotePcc: AntPlusGenericControllableDevicePcc? = null
    private var remoteReleaseHandle: PccReleaseHandle<AntPlusGenericControllableDevicePcc?>? = null

    private val mRemoteResultReceiver =
        IPluginAccessResultReceiver { result: AntPlusGenericControllableDevicePcc?, resultCode: RequestAccessResult?, initialDeviceState: DeviceState? ->
            if (resultCode === RequestAccessResult.SUCCESS) {
                remotePcc = result
            }
        }

    private val mRemoteDeviceStateChangeReceiver =
        IDeviceStateChangeReceiver { newDeviceState: DeviceState? ->
            //Timber.d(remotePcc?.getDeviceName() + " onDeviceStateChange:" + newDeviceState)
            if (newDeviceState === DeviceState.DEAD) {
                Timber.d("ANT DEAD")
                close_ant_handler(remoteReleaseHandle)
                remote_key()
            }
        }

    private val mRemoteCommand =
        IGenericCommandReceiver { estTimestamp: Long, eventFlags: EnumSet<EventFlag?>?, serialNumber: Int, manufacturerID: Int, sequenceNumber: Int, commandNumber: GenericCommandNumber? ->
            this.onNewGenericCommand(
                estTimestamp,
                eventFlags,
                serialNumber,
                manufacturerID,
                sequenceNumber,
                commandNumber
            )
        }



    private fun remote_key() {
        Timber.d("Remote Key STARTED")

        /* close_ant_handler(remoteReleaseHandle) */
        if (remoteReleaseHandle == null) {
            remoteReleaseHandle = AntPlusGenericControllableDevicePcc.requestAccess(
                this,
                mRemoteResultReceiver,
                mRemoteDeviceStateChangeReceiver,
                mRemoteCommand,
                0
            )
        }
    }


    private fun close_ant_handler(remHandle: PccReleaseHandle<AntPlusGenericControllableDevicePcc?>?) {
        Timber.d("Close ant handler")
        if (remHandle != null) remHandle.close()
    }

    private fun onNewGenericCommand(
        estTimestamp: Long,
        eventFlags: EnumSet<EventFlag?>?,
        serialNumber: Int,
        manufacturerID: Int,
        sequenceNumber: Int,
        commandNumber: GenericCommandNumber?
    ): CommandStatus {
       // Timber.d("Button pressed : %s", commandNumber)
        val actionthread = Thread(Runnable {
            Timber.d("Thread Started N:%s", Thread.currentThread().getName())
            if (true) {
                if (commandNumber == GenericCommandNumber.MENU_DOWN) {
                    Timber.d("IN ANTPLUS RIGHT")
                    karooSystem.dispatch(TurnScreenOn)
                    karooSystem.dispatch(PerformHardwareAction.TopRightPress)
                }
                if (commandNumber == GenericCommandNumber.LAP) {
                    Timber.d("IN ANTPLUS BACK")
                    karooSystem.dispatch(TurnScreenOn)
                    karooSystem.dispatch(PerformHardwareAction.BottomLeftPress)
                }
                if (commandNumber == GenericCommandNumber.UNRECOGNIZED) {
                    Timber.d("IN ANTPLUS  MAP")
                    karooSystem.dispatch(TurnScreenOn)
                    karooSystem.dispatch(PerformHardwareAction.BottomRightPress)
                }
            }
        })
        actionthread.start()
        return CommandStatus.PASS
    }

        /// end ANT Remote Code


    override fun onDestroy() {
        close_ant_handler(remoteReleaseHandle)
        karooSystem.disconnect()
        super.onDestroy()
    }
}