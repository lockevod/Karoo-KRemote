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
import com.enderthor.kremote.data.KeyLookup
import kotlinx.coroutines.flow.distinctUntilChanged
import com.enderthor.kremote.receiver.ConnectionServiceReceiver
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
    @Volatile private var activeDevice: RemoteDevice? = null
    @Volatile private var globalSettings: GlobalSettings? = null
    // Precomputed O(1) command → KarooKey table, rebuilt only when the active device's
    // learnedCommands actually change. Read on the hot ANT-callback path.
    @Volatile private var activeKeyLookup: KeyLookup = KeyLookup.EMPTY



    override fun onCreate() {
        super.onCreate()
        Timber.d("[KRemote] Extension onCreate - ID: ${System.identityHashCode(this)}")

        karooSystem = KarooSystemService(applicationContext)
        repository = RemoteRepository(applicationContext)


        _antManager = AntManager(applicationContext, { command, pressType ->
            // Hot path: lee directamente de AntManager (@Volatile), sin I/O
            if (_antManager.learningMode) {
                // Modo aprendizaje: comunicar comando a la app vía SharedPreferences
                extensionScope.launch(Dispatchers.IO) {
                    try {
                        val sharedPrefsCommands = applicationContext.getSharedPreferences(
                            "kremote_learned_commands", MODE_PRIVATE
                        )
                        sharedPrefsCommands.edit {
                            putString("last_command", command.name)
                            putString("last_press_type", pressType.name)
                            putLong("timestamp", System.currentTimeMillis())
                        }
                        Timber.d("📤 [KRemote] Comando enviado a app: ${command.name} (${pressType.name})")
                    } catch (e: Exception) {
                        Timber.e(e, "[KRemote] Error guardando comando aprendido")
                    }
                }
                return@AntManager
            }

            // Modo normal: ejecutar acción Karoo directamente
            if (::karooAction.isInitialized) {
                karooAction.handleAntCommand(command.gCommand, pressType)
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
                    { activeDevice },
                    { activeKeyLookup }
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
        // Estos consumers solo sirven para diagnóstico en desarrollo.
        // En release no se registran: evitan overhead de IPC y construcción de strings.
        if (!BuildConfig.DEBUG) return

        extensionScope.launch {
            suspendCancellableCoroutine { cont ->
                karooSystem.addConsumer { rideState: RideState ->
                    Timber.d("Ride state changed: $rideState")
                }
                karooSystem.addConsumer { navigationState: OnNavigationState ->
                    Timber.d("Navigation state changed: ${navigationState.state}")
                }
                karooSystem.addConsumer { event: OnGlobalPOIs ->
                    Timber.d("Global POIs changed: ${event.pois}")
                }
                karooSystem.addConsumer { event: SavedDevices ->
                    Timber.d("Saved devices changed: ${event.devices}")
                }
                karooSystem.addConsumer { event: Bikes ->
                    Timber.d("Bikes changed: ${event.bikes}")
                }
                karooSystem.addConsumer { event: ActiveRideProfile ->
                    Timber.d("ActiveRideProfile changed: ${event.profile}")
                }
                karooSystem.addConsumer { event: ActiveRidePage ->
                    Timber.d("ActiveRidePage changed: ${event.page}")
                }
                karooSystem.addConsumer { user: UserProfile ->
                    Timber.d("UserProfile changed: $user")
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

                        // La conexión es asíncrona: el resultado real llega vía
                        // mRemoteResultReceiver. Esperamos ~2s solo para que el log de
                        // abajo refleje el estado ya resuelto (en arranque en frío veíamos
                        // _isConnected=false hasta que llegaba el callback). Esto NO serializa
                        // contra monitorActiveDeviceChanges —corre en otra coroutine—; el
                        // doble-connect lo evita el guard isConnecting + throttle de connect().
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
                // distinctUntilChanged: el DataStore puede re-emitir el mismo JSON tras escrituras
                // no relacionadas; comparamos sólo lo que nos importa para reducir trabajo.
                repository.currentConfig
                    .distinctUntilChanged { a, b ->
                        val da = a.devices.find { it.isActive }
                        val db = b.devices.find { it.isActive }
                        da == db && a.globalSettings == b.globalSettings
                    }
                    .collect { config ->
                        val newActive = config.devices.find { it.isActive }
                        val previousActive = activeDevice
                        activeDevice = newActive
                        globalSettings = config.globalSettings

                        // Reconstruir el lookup sólo si han cambiado los comandos aprendidos.
                        val commandsChanged = previousActive?.learnedCommands != newActive?.learnedCommands
                        if (commandsChanged) {
                            activeKeyLookup = newActive?.buildKeyLookup() ?: KeyLookup.EMPTY
                        }

                        newActive?.doubleTapTimeout?.let { antManager.updateDoubleTapTimeout(it) }
                        antManager.updateDoubleTapEnabled(newActive?.enabledDoubleTap == true)

                        val mac = newActive?.macAddress
                        if (mac != null) {
                            try {
                                val deviceNumber = mac.toInt()
                                if (!antManager.isConnectedToDevice(deviceNumber)) {
                                    Timber.d("[KRemote] Conectando a dispositivo #$deviceNumber")
                                    antManager.connect(deviceNumber)
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "[KRemote] Error conectando dispositivo ANT+")
                            }
                        } else if (antManager.isConnected) {
                            antManager.disconnect()
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
            isRiding = isRideActive
            PerformanceOptimizer.setRidingState(isRideActive)

            // Debug-only: log de estado de configuración (evita DataStore I/O en release)
            if (DebugLogger.isEnabled()) {
                DebugLogger.logConnectionEvent(
                    deviceNumber = 0,
                    event = "RIDE_STATE_CHANGED",
                    details = "Ride active: $isRideActive",
                    source = "KremoteExtension"
                )
                extensionScope.launch {
                    try {
                        val currentConfig = repository.currentConfig.first()
                        DebugLogger.logConnectionEvent(
                            deviceNumber = 0,
                            event = "RIDE_CONFIG_CHECK",
                            details = "OnlyWhileRiding: ${currentConfig.globalSettings.onlyWhileRiding}, Riding: $isRideActive",
                            source = "KremoteExtension"
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Error getting current config for ride state logging")
                    }
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
            if (::karooAction.isInitialized) karooAction.cleanup()
            karooSystem.disconnect()
            extensionScope.cancel()

        } catch (e: Exception) {
            Timber.e(e, "Error during onDestroy")
        }
    }
}
