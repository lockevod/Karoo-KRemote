package com.enderthor.kremote.extension

import android.annotation.SuppressLint
import android.content.Intent

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RequestAnt


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

import timber.log.Timber

import com.enderthor.kremote.BuildConfig
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.DEFAULT_DOUBLE_TAP_TIMEOUT
import com.enderthor.kremote.data.EXTENSION_NAME
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.GlobalSettings
import com.enderthor.kremote.receiver.ConnectionServiceReceiver
import com.enderthor.kremote.data.PressType



class KremoteExtension : KarooExtension(EXTENSION_NAME, BuildConfig.VERSION_NAME) {

    companion object {
        @Volatile
        private var instance: KremoteExtension? = null

        fun getInstance(): KremoteExtension? = instance
    }

    init {
        instance = this
    }

    internal lateinit var karooSystem: KarooSystemService
    private lateinit var _antManager: AntManager
    private lateinit var repository: RemoteRepository
    private lateinit var karooAction: KarooAction

    val antManager: AntManager get() = _antManager

    private var rideReceiver: KarooRideReceiver? = null
    private var isRiding = false
    private var isServiceConnected = false
    private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDevice: RemoteDevice? = null
    private var globalSettings: GlobalSettings? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("[KRemote] Extension onCreate - ID: ${System.identityHashCode(this)}")

        karooSystem = KarooSystemService(applicationContext)
        repository = RemoteRepository(applicationContext)


        _antManager = AntManager(applicationContext, { command, pressType ->
            Timber.d("[KRemote] Comando ANT recibido en extensión: ${command.label} (${if(pressType == PressType.DOUBLE) "DOBLE" else "SIMPLE"})")
            extensionScope.launch(Dispatchers.Main) {
                try {
                    if (::karooAction.isInitialized) {
                        karooAction.handleAntCommand(command.gCommand, pressType)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[KRemote] Error procesando comando ANT")
                }
            }
        }, DEFAULT_DOUBLE_TAP_TIMEOUT)


        karooSystem.connect { connected ->
            Timber.i("[KRemote] Karoo service connected: $connected")
            isServiceConnected = connected

            if (connected) {

                karooAction = KarooAction(
                    karooSystem,
                    { isServiceConnected },
                    { isRiding },
                    { globalSettings?.onlyWhileRiding != false },
                    { globalSettings?.isForcedScreenOn != false},
                    { activeDevice }
                )


                karooSystem.dispatch(RequestAnt(EXTENSION_NAME))
                Timber.d("[KRemote] Solicitado acceso ANT+")


                connectActiveDevice()
            }
        }

        monitorActiveDeviceChanges()
        startConnectionService()
        initializeRideReceiver()
    }

    private fun connectActiveDevice() {
        extensionScope.launch {
            try {

                val device = repository.getActiveDevice().first()
                if (device != null) {
                    val deviceId = device.macAddress?.toInt()
                    if (deviceId != null) {
                        Timber.d("[KRemote] Conectando a dispositivo #$deviceId")


                        antManager.connect(deviceId)

                        delay(2000)
                        if (antManager.isConnectedToDevice(deviceId)) {
                            Timber.d("[KRemote] Conexión exitosa a dispositivo ANT+ #$deviceId")
                        } else {
                            Timber.d("[KRemote] No se pudo conectar a dispositivo ANT+ #$deviceId")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[KRemote] Error conectando dispositivo")
            }
        }
    }

   private fun monitorActiveDeviceChanges() {
        extensionScope.launch {
            try {
                repository.currentConfig.collect { config ->
                    activeDevice = config.devices.find { it.isActive }
                    globalSettings = config.globalSettings

                    activeDevice?.doubleTapTimeout?.let { timeout ->
                        antManager.updateDoubleTapTimeout(timeout)
                    }

                    if (activeDevice?.macAddress != null) {
                        try {
                            val deviceNumber = activeDevice?.macAddress?.toInt()
                            if (deviceNumber != null) {
                                if (!antManager.isConnectedToDevice(deviceNumber)) {
                                    Timber.d("[KRemote] Conectando a dispositivo #$deviceNumber (no conectado o dispositivo incorrecto)")
                                    antManager.connect(deviceNumber)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "[KRemote] Error conectando dispositivo ANT+")
                        }
                    } else {
                        if (antManager.isConnected) {
                            antManager.disconnect()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[KRemote] Error monitorizando cambios de dispositivo")
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun initializeRideReceiver() {
        rideReceiver = KarooRideReceiver { isRideActive ->
            Timber.d("Ride state changed: active = $isRideActive")
            isRiding = isRideActive
        }

        rideReceiver?.let { receiver ->
            try {
                applicationContext.registerReceiver(
                    receiver,
                    KarooRideReceiver.getIntentFilter()
                )
                Timber.d("Ride receiver registrado correctamente")
            } catch (e: Exception) {
                Timber.e(e, "Error al registrar ride receiver")
            }
        }
    }

    private fun startConnectionService() {
        try {
            val intent = Intent("com.enderthor.kremote.START_CONNECTION_SERVICE")
            intent.putExtra(ConnectionServiceReceiver.EXTRA_IS_EXTENSION, true)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Timber.e(e, "Error starting ConnectionService")
        }
    }

    override fun onDestroy() {
        Timber.d("KremoteExtension onDestroy")
        try {
            instance = null
            rideReceiver?.let { receiver ->
                try {
                    applicationContext.unregisterReceiver(receiver)
                    Timber.d("Ride receiver unregistered successfully")
                } catch (e: Exception) {
                    Timber.e(e, "Error unregistering ride receiver")
                }
            }
            rideReceiver = null

            antManager.disconnect()
            antManager.cleanup()
            karooSystem.disconnect()
            extensionScope.cancel()

        } catch (e: Exception) {
            Timber.e(e, "Error during extension destruction")
        }
        finally {
            super.onDestroy()
        }
    }
}