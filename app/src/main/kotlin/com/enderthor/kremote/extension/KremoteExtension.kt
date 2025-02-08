package com.enderthor.kremote.extension


import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
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
import io.hammerhead.karooext.models.ShowMapPage


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

import com.enderthor.kremote.BuildConfig

import timber.log.Timber
import java.util.EnumSet

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
//data class combinedSettings(val settings: RemoteSettings, val active: Boolean)

class KremoteExtension : KarooExtension("kremote", BuildConfig.VERSION_NAME) {

    lateinit var karooSystem: KarooSystemService
    private lateinit var rideAppReceiver: RideAppReceiver
    var onRideApp: Boolean = false

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect{ connected ->
            Timber.i( "Karoo system service connected: $connected")
        }
        Timber.d("oncreate")
        remote_key()

        rideAppReceiver = RideAppReceiver(this)

        val filter = IntentFilter().apply {
            addAction("io.hammerhead.intent.action.RIDE_APP_OPENED")
            addAction("io.hammerhead.hx.intent.action.RIDE_STOP")
        }
        registerReceiver(rideAppReceiver, filter)
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
        remHandle?.close()
    }

    fun onNewGenericCommand(
        estTimestamp: Long,
        eventFlags: EnumSet<EventFlag?>?,
        serialNumber: Int,
        manufacturerID: Int,
        sequenceNumber: Int,
        commandNumber: GenericCommandNumber?
    ): CommandStatus {

        CoroutineScope(Dispatchers.IO).launch {

            applicationContext
            .streamSettings()
            /*.combine(karooSystem.streamRideState()) { settings, rideState ->
                //combinedSettings(settings, !settings.onlyWhileRiding  || rideState is RideState.Recording)
                combinedSettings(settings, rideState is RideState.Recording)
            }
            .collectLatest { (settings, active) ->
                if (active) {*/
                .collectLatest { settings ->
                    fun sendkaction(action: PerformHardwareAction) {
                        karooSystem.dispatch(TurnScreenOn)

                        if( action == PerformHardwareAction.DrawerActionComboPress) karooSystem.dispatch(ShowMapPage(true))
                        else karooSystem.dispatch(action)
                        /*when (action) {
                            is PerformHardwareAction -> {karooSystem.dispatch(action)
                            "Map" -> {
                                Timber.d("IN Map sendaction")
                                karooSystem.dispatch(ShowMapPage(true))
                            }
                        }*/
                    }
                    if (onRideApp || !settings.onlyWhileRiding)
                    {
                        when (commandNumber) {
                            GenericCommandNumber.MENU_DOWN -> {
                                Timber.d("IN ANTPLUS RIGHT ${settings.remoteright.action}")
                                sendkaction(settings.remoteright.action)
                            }
                            GenericCommandNumber.LAP -> {
                                Timber.d("IN ANTPLUS BACK ${settings.remoteleft.action}")
                                sendkaction(settings.remoteleft.action)
                            }
                            GenericCommandNumber.UNRECOGNIZED -> {
                                Timber.d("IN ANTPLUS MAP ${settings.remoteup.action}")
                                sendkaction(settings.remoteup.action)
                            }
                            else -> {
                                Timber.d("IN ANTPLUS UNHANDLED COMMAND")
                            }
                        }
                    }else Timber.d("On Ride not active")
                }

            //}

        }
        return CommandStatus.PASS
    }
        /// end ANT Remote Code

    override fun onDestroy() {
        close_ant_handler(remoteReleaseHandle)
        unregisterReceiver(rideAppReceiver)
        karooSystem.disconnect()
        super.onDestroy()
    }
}
