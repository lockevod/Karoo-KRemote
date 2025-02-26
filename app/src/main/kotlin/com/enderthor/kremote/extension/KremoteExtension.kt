package com.enderthor.kremote.extension

import android.annotation.SuppressLint
import android.content.Intent

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RequestAnt


import kotlinx.coroutines.CoroutineScope


import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

import timber.log.Timber

import com.enderthor.kremote.BuildConfig
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.EXTENSION_NAME
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.GlobalSettings
import com.enderthor.kremote.receiver.ConnectionServiceReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob


class KremoteExtension : KarooExtension(EXTENSION_NAME, BuildConfig.VERSION_NAME) {

    companion object {
        @Volatile
        private var instance: KremoteExtension? = null

        fun getInstance(): KremoteExtension? = instance
    }

    init {
        instance = this
    }

    private lateinit var karooSystem: KarooSystemService
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

    private val extensionId = System.identityHashCode(this)
    private val antCallback: (AntRemoteKey) -> Unit = { antRemoteKey ->  // Cambiado a AntRemoteKey
        Timber.d("[KRemote] === INICIO Callback ANT ===")
        Timber.d("[KRemote] Callback hash: ${System.identityHashCode(this)}")

        extensionScope.launch(Dispatchers.Main) {
            try {
                karooAction.handleAntCommand(antRemoteKey.gCommand)  // Usar gCommand para mantener compatibilidad
            } catch (e: Exception) {
                Timber.e(e, "[KRemote] Error en callback ANT: ${e.message}")
            }
        }

        Timber.d("[KRemote] === FIN Callback ANT ===")
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("KREMOTE EXTENSION onCreate")

        karooSystem = KarooSystemService(applicationContext)
        repository = RemoteRepository(applicationContext)

        Timber.d("[KRemote] === Inicialización de AntManager ===")
        Timber.d("[KRemote] KremoteExtension hash: $extensionId")

        karooSystem.connect { connected ->
            Timber.i("Karoo system service connected: $connected")
            isServiceConnected = connected
            if (connected) {
                karooAction = KarooAction(
                    karooSystem,
                    { isServiceConnected },
                    { isRiding },
                    { globalSettings?.onlyWhileRiding != false },
                    { activeDevice }
                )
                karooSystem.dispatch(RequestAnt(EXTENSION_NAME))
            }
        }

        _antManager = AntManager(applicationContext, antCallback).also { manager ->
            Timber.d("[KRemote] AntManager creado con callback hash: ${System.identityHashCode(antCallback)}")
        }
        monitorActiveDeviceChanges()
        startConnectionService()
        initializeRideReceiver()
    }

    private fun monitorActiveDeviceChanges() {
        extensionScope.launch {
            try {
                repository.currentConfig.collect { config ->
                    activeDevice = config.devices.find { it.isActive }
                    globalSettings = config.globalSettings

                    // Manejar conexión ANT+
                    if (activeDevice?.macAddress != null) {
                        try {
                            val deviceNumber = activeDevice?.macAddress?.toInt()
                            if (!antManager.isConnected && deviceNumber != null) {
                                antManager.connect(deviceNumber)
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
                // Registrar sin la flag RECEIVER_NOT_EXPORTED
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