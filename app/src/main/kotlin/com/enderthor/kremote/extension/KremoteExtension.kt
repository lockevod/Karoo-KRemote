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
import com.enderthor.kremote.data.getLabelString
import com.enderthor.kremote.utils.DebugLogger



import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices

import kotlinx.coroutines.suspendCancellableCoroutine



import io.hammerhead.karooext.models.OnGlobalPOIs

import io.hammerhead.karooext.models.UserProfile
import androidx.core.content.edit
import com.enderthor.kremote.utils.PerformanceOptimizer


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
            Timber.d("[KRemote] ANT command received in extension: ${command.getLabelString(applicationContext)} (${if(pressType == PressType.DOUBLE) "DOUBLE" else "SINGLE"})")
            extensionScope.launch(Dispatchers.Main) {
                try {

                    val sharedPrefs = applicationContext.getSharedPreferences("kremote_state",
                        MODE_PRIVATE
                    )
                    val isLearningMode = sharedPrefs.getBoolean("learning_mode", false)

                    Timber.d("🔍 [KRemote] DIAGNÓSTICO APRENDIZAJE:")
                    Timber.d("   ├── SharedPreferences learning_mode: $isLearningMode")
                    Timber.d("   ├── AntManager learningMode actual: ${_antManager.learningMode}")

                    // Sync with  AntManager local
                    _antManager.setLearningMode(isLearningMode)

                    Timber.d("   └── AntManager learningMode after sync: ${_antManager.learningMode}")

                    if (isLearningMode) {
                        Timber.d("🎓 [KRemote] MODO APRENDIZAJE: Comando detectado sin restricciones (sincronizado desde app)")

                        // NUEVO: Comunicar el comando detectado de vuelta a la aplicación
                        val sharedPrefsCommands = applicationContext.getSharedPreferences("kremote_learned_commands",
                            MODE_PRIVATE
                        )
                        val currentTime = System.currentTimeMillis()
                        sharedPrefsCommands.edit {
                            putString("last_command", command.name)
                            putString("last_press_type", pressType.name)
                            putLong("timestamp", currentTime)
                        }

                        Timber.d("📤 [KRemote] Comando enviado a app: ${command.name} (${pressType.name})")

                        // En modo aprendizaje, no aplicar restricciones de riding
                        // El comando se procesará directamente por el DeviceViewModel
                        return@launch
                    }

                    // Solo aplicar restricciones de riding cuando NO estamos aprendiendo
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
                    applicationContext,
                    { isServiceConnected },
                    { isRiding },
                    { globalSettings?.onlyWhileRiding ?: false },
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
        initializeEvents()
    }

    private fun initializeEvents() {
        extensionScope.launch {
            suspendCancellableCoroutine { cont ->
                karooSystem.addConsumer { rideState: RideState ->
                    Timber.w("Ride state changed: $rideState")
                }
                karooSystem.addConsumer { navigationState: OnNavigationState ->
                    Timber.w("Navigation state changed: $navigationState")
                    Timber.w("Navigation state changed: ${navigationState.state}")
                }
                karooSystem.addConsumer { event: OnGlobalPOIs ->
                    Timber.w("Global POIs changed: $event")
                    Timber.w("Global POIs changed: ${event.pois}")
                }
                karooSystem.addConsumer { event: SavedDevices ->
                    Timber.w("Saved devices changed: $event")
                    Timber.w("Saved devices changed: ${event.devices}")
                }
                karooSystem.addConsumer { event: Bikes ->
                    Timber.w("Bikes changed: $event")
                    Timber.w("Bikes changed: ${event.bikes}")
                }
                karooSystem.addConsumer { event: ActiveRideProfile ->
                    Timber.w("ActiveRideProfile changed: $event")
                    Timber.w("ActiveRideProfile changed: ${event.profile}")
                }
                karooSystem.addConsumer { event: ActiveRidePage ->
                    Timber.w("ActiveRidePage changed: $event")
                    Timber.w("ActiveRidePage changed: ${event.page}")
                }

                karooSystem.addConsumer { user: UserProfile ->
                    Timber.w("UserProfile changed: $user")
                }

                karooSystem.addConsumer { event: OnGlobalPOIs ->
                    Timber.w("UserProfile changed: $event")
                }

            }
        }
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
                            Timber.d("[KRemote] Successful connection to ANT+ device #$deviceId")
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
            DebugLogger.logConnectionEvent(
                deviceNumber = 0,
                event = "RIDE_STATE_CHANGED",
                details = "Ride active: $isRideActive (previous: $isRiding)",
                source = "KremoteExtension"
            )
            isRiding = isRideActive
            
            // Notificar al sistema de heartbeat del cambio de estado de riding
            PerformanceOptimizer.setRidingState(isRideActive)

            // Log adicional para verificar configuraciones relacionadas
            extensionScope.launch {
                try {
                    val currentConfig = repository.currentConfig.first()
                    val onlyWhileRiding = currentConfig.globalSettings.onlyWhileRiding
                    DebugLogger.logConnectionEvent(
                        deviceNumber = 0,
                        event = "RIDE_CONFIG_CHECK",
                        details = "OnlyWhileRiding setting: $onlyWhileRiding, Current riding state: $isRideActive, Heartbeat mode: ${if (isRideActive) "CRITICAL" else "NORMAL"}",
                        source = "KremoteExtension"
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Error getting current config for ride state logging")
                }
            }
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
            DebugLogger.logConnectionEvent(0, "EXTENSION_START_SERVICE", "Attempting to start ConnectionService via broadcast", "KremoteExtension")
            Timber.d("[KremoteExtension] Sending broadcast to start ConnectionService")

            val intent = Intent("com.enderthor.kremote.START_CONNECTION_SERVICE")
            intent.putExtra(ConnectionServiceReceiver.EXTRA_IS_EXTENSION, true)
            //  Usar el permiso requerido por el receiver
            sendBroadcast(intent, "com.enderthor.kremote.PERMISSION_START_CONNECTION")

            DebugLogger.logConnectionEvent(0, "EXTENSION_BROADCAST_SENT", "Broadcast sent successfully with permission", "KremoteExtension")
            Timber.d("[KremoteExtension] Broadcast sent it with permission to start ConnectionService")
        } catch (e: Exception) {
            DebugLogger.logError("EXTENSION", "Error starting ConnectionService", e, "KremoteExtension")
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
            Timber.e(e, "Error during onDestroy")
        }
    }
}
