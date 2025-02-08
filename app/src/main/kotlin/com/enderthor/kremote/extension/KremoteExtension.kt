package com.enderthor.kremote.extension

import android.content.Intent
import androidx.core.content.ContextCompat
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.ShowMapPage

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

import timber.log.Timber

import com.enderthor.kremote.bluetooth.BluetoothManager
import com.enderthor.kremote.bluetooth.BluetoothService
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.GlobalConfig
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.service.ConnectionService

class KremoteExtension : KarooExtension("kremote", "1.5") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var antManager: AntManager
    private lateinit var repository: RemoteRepository
    private var rideReceiver: KarooRideReceiver? = null

    private var currentBluetoothService: BluetoothService? = null
    private var isRiding = false
    private var isServiceConnected = false
    private var extensionScope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("KremoteExtension onCreate")

        initializeComponents()
        extensionScope = CoroutineScope(Dispatchers.IO + Job())
        initializeKarooSystem()
        initializeRideReceiver()
    }

    private fun initializeComponents() {
        repository = RemoteRepository(applicationContext)
        bluetoothManager = BluetoothManager(applicationContext)
        antManager = AntManager(applicationContext) { commandNumber ->
            handleAntCommand(commandNumber)
        }
    }

    private fun initializeKarooSystem() {
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            Timber.i("Karoo system service connected: $connected")
            isServiceConnected = connected
            if (connected) {
                initializeBluetoothAndSettings()
                startConnectionService()
            }
        }
    }

    private fun initializeRideReceiver() {
        rideReceiver = KarooRideReceiver { isRideActive ->
            Timber.d("Ride state changed: active = $isRideActive")
            isRiding = isRideActive
            handleRideStateChange(isRideActive)
        }

        rideReceiver?.let { receiver ->
            try {
                ContextCompat.registerReceiver(
                    applicationContext,
                    receiver,
                    KarooRideReceiver.getIntentFilter(),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                Timber.d("Ride receiver registered successfully")
            } catch (e: Exception) {
                Timber.e(e, "Error registering ride receiver")
            }
        }
    }

    private fun handleRideStateChange(isRideActive: Boolean) {
        extensionScope?.launch {
            try {
                val config = repository.currentConfig.first()
                if (isRideActive) {
                    Timber.d("Ride started, checking connections")
                    if (isServiceConnected && config.globalSettings.onlyWhileRiding) {
                        connectActiveDevices(config)
                    }
                } else {
                    Timber.d("Ride stopped, checking disconnections")
                    if (config.globalSettings.onlyWhileRiding) {
                        disconnectDevices()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling ride state change")
            }
        }
    }

    private fun initializeBluetoothAndSettings() {
        extensionScope?.launch {
            try {
                val config = repository.currentConfig.first()
                if (config.globalSettings.autoConnectOnStart && !config.globalSettings.onlyWhileRiding) {
                    connectActiveDevices(config)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error initializing settings")
            }
        }
    }

    private fun startConnectionService() {
        try {
            val serviceIntent = Intent(applicationContext, ConnectionService::class.java)
            applicationContext.startService(serviceIntent)
        } catch (e: Exception) {
            Timber.e(e, "Error starting ConnectionService")
        }
    }

    private fun disconnectDevices() {
        Timber.d("Disconnecting all devices")
        currentBluetoothService?.disconnect()
        currentBluetoothService = null
        antManager.disconnect()
    }

    private fun connectActiveDevices(config: GlobalConfig) {
        config.devices.filter { it.isActive }.forEach { device ->
            try {
                when (device.type) {
                    RemoteType.ANT -> {
                        Timber.d("Initializing ANT+ remote")
                        antManager.connect()
                    }
                    RemoteType.BLUETOOTH -> {
                        Timber.d("Connecting Bluetooth device: ${device.name}")
                        device.macAddress?.let { mac ->
                            connectBluetoothDevice(device.id, mac)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error connecting device: ${device.name}")
            }
        }
    }

    private fun connectBluetoothDevice(deviceId: String, macAddress: String) {
        try {
            val btDevice = bluetoothManager.getBluetoothDeviceByAddress(macAddress)
            currentBluetoothService = bluetoothManager.createBluetoothService { keyCode ->
                handleBluetoothKeyPress(keyCode)
            }
            btDevice?.let { device ->
                currentBluetoothService?.connect(device)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error connecting Bluetooth device: $macAddress")
        }
    }

    private fun handleAntCommand(commandNumber: GenericCommandNumber) {
        extensionScope?.launch {
            try {
                if (!isRiding) {
                    Timber.d("Ignoring command: not riding")
                    return@launch
                }

                val device = repository.getActiveDevice().first()
                when (commandNumber) {
                    GenericCommandNumber.MENU_DOWN -> {
                        Timber.d("ANT+ Right button: ${device?.keyMappings?.remoteright?.action}")
                        device?.keyMappings?.remoteright?.action?.let { executeKarooAction(it) }
                    }
                    GenericCommandNumber.LAP -> {
                        Timber.d("ANT+ Left button: ${device?.keyMappings?.remoteleft?.action}")
                        device?.keyMappings?.remoteleft?.action?.let { executeKarooAction(it) }
                    }
                    GenericCommandNumber.UNRECOGNIZED -> {
                        Timber.d("ANT+ Up button: ${device?.keyMappings?.remoteup?.action}")
                        device?.keyMappings?.remoteup?.action?.let { executeKarooAction(it) }
                    }
                    else -> {
                        Timber.d("Unhandled ANT+ command: $commandNumber")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling ANT+ command")
            }
        }
    }

    private fun handleBluetoothKeyPress(keyCode: Int) {
        extensionScope?.launch {
            try {
                if (!isRiding) {
                    Timber.d("Ignoring Bluetooth command: not riding")
                    return@launch
                }

                val device = repository.getActiveDevice().first()
                when (keyCode) {
                    1 -> { // LEFT
                        Timber.d("Bluetooth Left button: ${device?.keyMappings?.remoteleft?.action}")
                        device?.keyMappings?.remoteleft?.action?.let { executeKarooAction(it) }
                    }
                    2 -> { // RIGHT
                        Timber.d("Bluetooth Right button: ${device?.keyMappings?.remoteright?.action}")
                        device?.keyMappings?.remoteright?.action?.let { executeKarooAction(it) }
                    }
                    3 -> { // UP
                        Timber.d("Bluetooth Up button: ${device?.keyMappings?.remoteup?.action}")
                        device?.keyMappings?.remoteup?.action?.let { executeKarooAction(it) }
                    }
                    else -> Timber.d("Unhandled Bluetooth keycode: $keyCode")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling Bluetooth key press")
            }
        }
    }

    private fun executeKarooAction(action: PerformHardwareAction) {
        if (!isServiceConnected) {
            Timber.w("Cannot execute action: Karoo service not connected")
            return
        }

        karooSystem.dispatch(TurnScreenOn)

        if (action == PerformHardwareAction.DrawerActionComboPress) {
            karooSystem.dispatch(ShowMapPage(true))
        } else {
            karooSystem.dispatch(action)
        }
    }

    override fun onDestroy() {
        Timber.d("KremoteExtension onDestroy")
        try {
            rideReceiver?.let { receiver ->
                try {
                    applicationContext.unregisterReceiver(receiver)
                    Timber.d("Ride receiver unregistered successfully")
                } catch (e: Exception) {
                    Timber.e(e, "Error unregistering ride receiver")
                }
            }
            rideReceiver = null

            disconnectDevices()
            karooSystem.disconnect()
            extensionScope?.cancel()
            extensionScope = null
            bluetoothManager.cleanup()
            antManager.cleanup()
        } catch (e: Exception) {
            Timber.e(e, "Error during extension destruction")
        }
        super.onDestroy()
    }
}