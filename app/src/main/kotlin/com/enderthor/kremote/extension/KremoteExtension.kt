package com.enderthor.kremote.extension

import android.annotation.SuppressLint
import android.content.Intent

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.ShowMapPage
import io.hammerhead.karooext.models.RequestAnt

import io.hammerhead.karooext.models.ReleaseAnt
import io.hammerhead.karooext.models.ReleaseBluetooth

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

import timber.log.Timber

import com.enderthor.kremote.BuildConfig
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.EXTENSION_NAME
import com.enderthor.kremote.data.GlobalConfig
import com.enderthor.kremote.data.RemoteRepository

import com.enderthor.kremote.receiver.ConnectionServiceReceiver


class KremoteExtension : KarooExtension(EXTENSION_NAME, BuildConfig.VERSION_NAME) {

    companion object {
        @Volatile
        private var instance: KremoteExtension? = null

        fun getInstance(): KremoteExtension? = instance
    }

    init {
        instance = this
    }

    private val extensionId = System.identityHashCode(this)

    private lateinit var karooSystem: KarooSystemService
    private lateinit var antManager: AntManager
    private lateinit var repository: RemoteRepository

    private var rideReceiver: KarooRideReceiver? = null
    private var isRiding = false
    private var isServiceConnected = false
    private var extensionScope: CoroutineScope? = null




    private val antCallback: (GenericCommandNumber) -> Unit = { commandNumber ->
        Timber.d("[KRemote] === INICIO Callback ANT ===")
        Timber.d("[KRemote] Callback hash: ${System.identityHashCode(this)}")

        extensionScope?.launch(Dispatchers.Main) {
            try {
                handleAntCommand(commandNumber)
            } catch (e: Exception) {
                Timber.e(e, "[KRemote] Error en callback ANT: ${e.message}")
            }
        } ?: Timber.e("[KRemote] extensionScope es null")

        Timber.d("[KRemote] === FIN Callback ANT ===")
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("KREMOTE EXTENSION onCreate")

        initializeComponents()
        extensionScope = CoroutineScope(Dispatchers.IO + Job())
        initializeKarooSystem()
        initializeRideReceiver()
    }



    private fun initializeComponents() {
        repository = RemoteRepository(applicationContext)

        Timber.d("[KRemote] === Inicialización de AntManager ===")
        Timber.d("[KRemote] KremoteExtension hash: $extensionId")

        antManager = AntManager(applicationContext, antCallback).also { manager ->
            Timber.d("[KRemote] AntManager creado con callback hash: ${System.identityHashCode(antCallback)}")
        }
    }

    private fun initializeKarooSystem() {
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            Timber.i("Karoo system service connected: $connected")
            isServiceConnected = connected
            if (connected) {
                initializeSettings()
                startConnectionService()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun initializeRideReceiver() {
        rideReceiver = KarooRideReceiver { isRideActive ->
            Timber.d("Ride state changed: active = $isRideActive")
            isRiding = isRideActive
            handleRideStateChange(isRideActive)
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

    private fun initializeSettings() {
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
            val intent = Intent("com.enderthor.kremote.START_CONNECTION_SERVICE")
            intent.putExtra(ConnectionServiceReceiver.EXTRA_IS_EXTENSION, true)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Timber.e(e, "Error starting ConnectionService")
        }
    }

    fun connectAntDevice(deviceNumber: Int) {
        Timber.d("[KRemote] Conectando dispositivo ANT #$deviceNumber")
        extensionScope?.launch {
            try {
                antManager.connect(deviceNumber)
            } catch (e: Exception) {
                Timber.e(e, "[KRemote] Error conectando dispositivo ANT: ${e.message}")
            }
        }
    }

    fun isAntConnected(): Boolean = antManager.isConnected


    fun requestConnection(enable: Boolean) {

        val request = RequestAnt(extension)
        val release = ReleaseAnt(extension)

        if (enable) {
            karooSystem.dispatch(request)
        } else {
            karooSystem.dispatch(release)
        }

    }


    private fun connectActiveDevices(config: GlobalConfig) {
        config.devices.filter { it.isActive }.forEach { device ->
            try {
                Timber.d("Initializing ANT+ remote")
                karooSystem.dispatch(RequestAnt(extension))
                device.macAddress?.let { mac ->
                    extensionScope?.launch {
                        antManager.connect(mac.toInt())
                    }

                }
            } catch (e: Exception) {
                Timber.e(e, "Error connecting device: ${device.name}")
            }
        }
    }

    private fun disconnectDevices() {
        Timber.d("Disconnecting all devices")
        try {

            antManager.disconnect()

            // Liberar los servicios del sistema
            if (isServiceConnected) {
                karooSystem.dispatch(ReleaseBluetooth(extension))
                karooSystem.dispatch(ReleaseAnt(extension))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting devices")
        }
    }


    private fun handleAntCommand(commandNumber: GenericCommandNumber) {
        Timber.d("[KRemote] handleAntCommand INICIO: $commandNumber")
        Timber.d("[KRemote] Estado actual - isServiceConnected: $isServiceConnected, isRiding: $isRiding")

        if (!isServiceConnected) {
            Timber.w("[KRemote] Servicio no conectado")
            return
        }

        if (!isRiding) {
            Timber.w("[KRemote] No está en modo riding")
            return
        }

        extensionScope?.launch(Dispatchers.Main) {
            try {
                val device = repository.getActiveDevice().first()
                Timber.d("[KRemote] Dispositivo activo: ${device?.name}")

                when (commandNumber) {
                    GenericCommandNumber.MENU_DOWN -> {
                        Timber.d("[KRemote] Procesando MENU_DOWN")
                        device?.keyMappings?.remoteright?.action?.let {
                            Timber.d("[KRemote] Ejecutando acción derecha: $it")
                            executeKarooAction(it)
                        }
                    }
                    GenericCommandNumber.LAP -> {
                        Timber.d("[KRemote] Procesando LAP")
                        device?.keyMappings?.remoteleft?.action?.let {
                            Timber.d("[KRemote] Ejecutando acción izquierda: $it")
                            executeKarooAction(it)
                        }
                    }
                    GenericCommandNumber.UNRECOGNIZED -> {
                        Timber.d("[KRemote] Procesando UNRECOGNIZED")
                        device?.keyMappings?.remoteup?.action?.let {
                            Timber.d("[KRemote] Ejecutando acción arriba: $it")
                            executeKarooAction(it)
                        }
                    }
                    else -> Timber.w("[KRemote] Comando no manejado: $commandNumber")
                }
            } catch (e: Exception) {
                Timber.e(e, "[KRemote] Error en handleAntCommand")
            }
        } ?: Timber.e("[KRemote] extensionScope es null")
    }



    private fun executeKarooAction(action: PerformHardwareAction) {
        Timber.d("executeKarooAction: $action")

        if (!isServiceConnected) {
            Timber.w("No se puede ejecutar acción: servicio Karoo no conectado")
            return
        }

        try {
            Timber.d("Enviando TurnScreenOn")
            karooSystem.dispatch(TurnScreenOn)

            if (action == PerformHardwareAction.DrawerActionComboPress) {
                Timber.d("Mostrando página de mapa")
                karooSystem.dispatch(ShowMapPage(true))
            } else {
                Timber.d("Enviando acción: $action")
                karooSystem.dispatch(action)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error ejecutando acción Karoo: $action")
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

            disconnectDevices() // Esto ya maneja la liberación de servicios
            karooSystem.disconnect()
            extensionScope?.cancel()
            extensionScope = null
            antManager.cleanup()
        } catch (e: Exception) {
            Timber.e(e, "Error during extension destruction")
        }
        finally {
            super.onDestroy()
        }
    }
}