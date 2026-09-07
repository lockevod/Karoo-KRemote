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
import com.enderthor.kremote.hal.BuzzerClient
import kotlinx.coroutines.flow.distinctUntilChanged
import com.enderthor.kremote.receiver.ConnectionServiceReceiver
import com.enderthor.kremote.utils.DebugLogger



import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices

import kotlinx.coroutines.awaitCancellation



import io.hammerhead.karooext.models.OnGlobalPOIs

import io.hammerhead.karooext.models.UserProfile
import androidx.core.content.edit
import com.enderthor.kremote.utils.PerformanceOptimizer


class KremoteExtension : KarooExtension(EXTENSION_NAME, BuildConfig.VERSION_NAME) {

    companion object {
        @Volatile
        private var instance: KremoteExtension? = null

        fun getInstance(): KremoteExtension? = instance

        private const val LEARNING_PREFS = "kremote_state"
        private const val LEARNING_KEY = "learning_mode"
        private const val LEARNING_TS_KEY = "learning_mode_ts"
        // La UI se auto-para a los 30 s; damos margen de sobra antes de considerarlo huérfano.
        private const val LEARNING_MODE_MAX_MS = 120_000L
    }

    init {
        instance = this
    }

    internal lateinit var karooSystem: KarooSystemService
    private lateinit var _antManager: AntManager
    private lateinit var repository: RemoteRepository
    // @Volatile como sus vecinos (activeDevice, globalSettings, activeKeyLookup): se escribe
    // desde el callback de karooSystem.connect y se lee desde el camino de la pulsación.
    @Volatile private lateinit var karooAction: KarooAction
    // Cliente HAL para el bypass del mute; el bind es asíncrono y se reutiliza durante toda
    // la vida de la extensión. Nullable: si el bind falla, KarooAction cae al beep del SDK.
    private var buzzerClient: BuzzerClient? = null

    val antManager: AntManager get() = _antManager

    private var rideReceiver: KarooRideReceiver? = null
    private var learningPrefs: android.content.SharedPreferences? = null
    private var learningPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var learningResetJob: kotlinx.coroutines.Job? = null
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

        // Lanzamos el bind del HAL ya en onCreate para que el binder esté listo antes del
        // primer beep. Es idempotente y barato; si no hay opt-in, KarooAction nunca lo usa.
        buzzerClient = BuzzerClient(applicationContext).also {
            Timber.d("[KRemote] BuzzerClient bind: ${it.connect()}")
        }


        _antManager = AntManager(applicationContext, { command, pressType ->
            // Hot path: lee directamente de AntManager (@Volatile), sin I/O
            if (_antManager.learningMode) {
                // Modo aprendizaje: comunicar comando a la app vía SharedPreferences
                extensionScope.launch(Dispatchers.IO) {
                    try {
                        val sharedPrefsCommands = applicationContext.getSharedPreferences(
                            "kremote_learned_commands", MODE_PRIVATE
                        )
                        // Sólo el botón: el aprendizaje descubre botones, no pulsaciones.
                        // (En modo aprendizaje AntManager ni siquiera pasa por el detector
                        // de doble toque, así que el pressType aquí era siempre SINGLE.)
                        sharedPrefsCommands.edit {
                            putString("last_command", command.name)
                            putLong("timestamp", System.currentTimeMillis())
                        }
                        Timber.d("📤 [KRemote] Botón enviado a app: ${command.name}")
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

                // Este callback se dispara en CADA reconexión del servicio Karoo, no sólo
                // en el arranque. El KarooAction anterior queda obsoleto: su actionScope
                // puede tener un zoom rápido en vuelo despachando contra la conexión que
                // acaba de reciclarse. No es una fuga acumulativa —un scope ocioso sin
                // hijos es basura recolectable, y el zoom se agota solo en ~300 ms—; es
                // cancelación de trabajo obsoleto. Se construye el nuevo PRIMERO y se
                // limpia el viejo DESPUÉS, para que el campo nunca apunte a un scope ya
                // cancelado. Contrapartida aceptada: una reconexión a mitad de zoom rápido
                // trunca las repeticiones que falten.
                val previousKarooAction = if (::karooAction.isInitialized) karooAction else null

                karooAction = KarooAction(
                    karooSystem,
                    applicationContext,
                    { isServiceConnected },
                    { isRiding },
                    { globalSettings?.onlyWhileRiding ?: false },
                    { globalSettings?.isForcedScreenOn != false},
                    { activeDevice },
                    { activeKeyLookup },
                    { globalSettings?.bypassMute == true },
                    buzzerClient
                )
                previousKarooAction?.cleanup()


                karooSystem.dispatch(RequestAnt(EXTENSION_NAME))
                Timber.d("[KRemote] Solicitado acceso ANT+")


                connectActiveDevice()
            }
        }

        observeLearningMode()
        monitorActiveDeviceChanges()
        startConnectionService()
        initializeRideReceiver()
        initializeEvents()
    }

    /**
     * Puente del modo aprendizaje entre la pantalla de ajustes y la extensión.
     *
     * `DeviceViewModel` escribía `kremote_state/learning_mode` "para sincronizar con la
     * extensión", pero nadie lo leía: la pantalla activa el modo aprendizaje sobre SU
     * propia instancia de AntManager (la de MainActivity), y la de la extensión seguía en
     * modo normal. Resultado: mientras el rider enseñaba un botón, la extensión ejecutaba
     * el mapeo ya existente — pausar la ruta, marcar vuelta, apagar la pantalla.
     *
     * Todo corre en el mismo proceso (no hay android:process en el manifiesto), así que un
     * listener de SharedPreferences es suficiente y no cuesta nada en el hot path: el
     * callback ANT sigue leyendo un simple @Volatile de AntManager.
     */
    private fun observeLearningMode() {
        val prefs = applicationContext.getSharedPreferences(LEARNING_PREFS, MODE_PRIVATE)
        learningPrefs = prefs

        // Estado inicial: sólo honramos un flag reciente. Uno viejo significa que la app
        // murió a mitad del aprendizaje y no debe dejar el mando inerte tras un reinicio.
        val stale = System.currentTimeMillis() - prefs.getLong(LEARNING_TS_KEY, 0L) >= LEARNING_MODE_MAX_MS
        applyLearningMode(prefs.getBoolean(LEARNING_KEY, false) && !stale)

        // Reaccionar TAMBIÉN al timestamp, no sólo al booleano. SharedPreferences no notifica
        // una escritura cuyo valor no cambia (commitToMemory descarta esas claves), y hay dos
        // caminos que dejan el flag a `true` en disco mientras en runtime ya volvimos a modo
        // normal: el job de reset de 120 s y la comprobación de caducidad del arranque. Con el
        // flag atascado en `true`, el siguiente startLearning() escribe true sobre true → sin
        // callback → la extensión nunca entra en modo aprendizaje y volvemos al defecto
        // original. startLearning() siempre escribe un timestamp NUEVO, así que esa clave sí
        // cambia y nos rearma. (Que salten las dos es inocuo: applyLearningMode es idempotente.)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == LEARNING_KEY || key == LEARNING_TS_KEY) {
                val enabled = p.getBoolean(LEARNING_KEY, false)
                Timber.d("[KRemote] Learning mode desde ajustes: $enabled")
                applyLearningMode(enabled)
            }
        }
        learningPrefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun applyLearningMode(enabled: Boolean) {
        learningResetJob?.cancel()
        learningResetJob = null
        _antManager.setLearningMode(enabled)

        if (enabled) {
            // Red de seguridad simétrica a la de arriba, para el caso de que la pantalla de
            // ajustes muera SIN escribir el false (su auto-stop es de 30 s). Sin esto el
            // mando se quedaría sin ejecutar acciones hasta reiniciar la extensión.
            learningResetJob = extensionScope.launch {
                delay(LEARNING_MODE_MAX_MS)
                Timber.w("[KRemote] Learning mode expirado — volviendo a modo normal")
                _antManager.setLearningMode(false)
            }
        }
    }

    private fun initializeEvents() {
        // Estos consumers solo sirven para diagnóstico en desarrollo.
        // En release no se registran: evitan overhead de IPC y construcción de strings.
        if (!BuildConfig.DEBUG) return

        extensionScope.launch {
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

            // Mantener la coroutine viva para que los consumers sigan registrados
            // hasta que se cancele el scope de la extensión.
            awaitCancellation()
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

            learningResetJob?.cancel()
            learningResetJob = null
            learningPrefsListener?.let { listener ->
                try {
                    learningPrefs?.unregisterOnSharedPreferenceChangeListener(listener)
                } catch (e: Exception) {
                    Timber.e(e, "Error unregistering learning mode listener")
                }
            }
            learningPrefsListener = null
            learningPrefs = null

            antManager.disconnect()
            antManager.cleanup()
            if (::karooAction.isInitialized) karooAction.cleanup()
            buzzerClient?.disconnect()
            buzzerClient = null
            karooSystem.disconnect()
            extensionScope.cancel()

        } catch (e: Exception) {
            Timber.e(e, "Error during onDestroy")
        }
    }
}
