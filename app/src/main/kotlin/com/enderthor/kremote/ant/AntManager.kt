package com.enderthor.kremote.ant

import android.content.Context
import android.os.SystemClock
import com.dsi.ant.plugins.antplus.pcc.controls.AntPlusGenericControllableDevicePcc
import com.dsi.ant.plugins.antplus.pcc.controls.defines.CommandStatus
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.DEFAULT_DOUBLE_TAP_TIMEOUT
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.data.getLabelString
import com.enderthor.kremote.data.minReconnectInterval
import com.enderthor.kremote.data.COMMAND_PROCESSING_DELAY_MS
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.PerformanceOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

import timber.log.Timber

data class AntDeviceInfo(
    val name: String,
    val deviceNumber: Int
)

class AntManager(
    private val context: Context,
    private var commandCallback: (AntRemoteKey, PressType) -> Unit,
    doubleTapTimeout: Long = DEFAULT_DOUBLE_TAP_TIMEOUT
) {
    // Escritos desde el binder thread ANT+ (mRemoteResultReceiver) y desde disconnect()
    // que se llama desde varios scopes. Leídos desde el binder en cada comando y desde
    // coroutines IO en isConnectedToDevice. Sin @Volatile un lector IO puede ver el
    // puntero antiguo no-null tras un disconnect en main → falso positivo de conexión.
    @Volatile private var remotePcc: AntPlusGenericControllableDevicePcc? = null
    @Volatile private var remoteReleaseHandle: PccReleaseHandle<AntPlusGenericControllableDevicePcc?>? = null


    private val _detectedDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val detectedDevices: StateFlow<List<AntDeviceInfo>> = _detectedDevices.asStateFlow()

    @Volatile private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    @Volatile private var _learningMode = false
    val learningMode: Boolean get() = _learningMode

    // Guardia del re-entry de connect(). Sin @Volatile, dos llamadas concurrentes podían
    // pasar el TOCTOU y disparar dos requestAccess seguidos, dejando el primer
    // remoteReleaseHandle huérfano. @Volatile no es atomic, pero combinado con el
    // throttle de minReconnectInterval cierra la ventana en la práctica.
    @Volatile private var isConnecting = false
    @Volatile private var lastConnectionAttempt = 0L

    // Per-device inline throttle (no coroutine allocation in hot path).
    // Antes era un único @Volatile global; volvemos a per-device para no perder
    // pulsaciones cuando hay varios mandos / canales activos. ConcurrentHashMap
    // garantiza visibilidad entre el callback ANT (binder) y otros hilos.
    private val lastCommandTimeByDevice = ConcurrentHashMap<Int, Long>()

    @Volatile private var doubleTapEnabled = false
    @Volatile private var doubleTapTimeout_field = doubleTapTimeout
    private var doubleTapDetector: DoubleTapDetector? = null

    init {
        doubleTapDetector = DoubleTapDetector(doubleTapTimeout_field, doubleTapEnabled) { commandNumber, pressType ->
            val antCommand = AntRemoteKey.byCommand[commandNumber]
            antCommand?.let {
                if (DebugLogger.isEnabled()) {
                    Timber.d("[ANT] Processing command: ${it.getLabelString(context)} (${if(pressType == PressType.DOUBLE) "DOUBLE" else "SINGLE"})")
                }
                commandCallback.invoke(it, pressType)
            }
        }
    }

    private val mRemoteResultReceiver =
        AntPluginPcc.IPluginAccessResultReceiver { result: AntPlusGenericControllableDevicePcc?,
                                                   resultCode: RequestAccessResult,
                                                   initialDeviceState: DeviceState ->
            val deviceNumber = result?.antDeviceNumber ?: 0
            when (resultCode) {
                RequestAccessResult.SUCCESS -> {
                    remotePcc = result
                    _isConnected = true
                    // OPTION B: Notify real ANT+ connection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, true)
                    
                    result?.let { pcc: AntPlusGenericControllableDevicePcc ->
                        val deviceInfo = AntDeviceInfo(
                            name = "ANT+ Remote #${pcc.antDeviceNumber}",
                            deviceNumber = pcc.antDeviceNumber
                        )

                        DebugLogger.logDeviceDetection(pcc.antDeviceNumber, deviceInfo.name)
                        DebugLogger.logConnectionEvent(pcc.antDeviceNumber, "CONNECTION_SUCCESS", "Initial state: $initialDeviceState")

                        if (!_detectedDevices.value.any { it.deviceNumber == deviceInfo.deviceNumber }) {
                            _detectedDevices.value = _detectedDevices.value + deviceInfo
                        }
                    }
                    Timber.d("ANT+ remote connected successfully deviceNumber: ${result?.antDeviceNumber}")
                }

                RequestAccessResult.USER_CANCELLED -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_FAILED", "User cancelled")
                    Timber.w("User cancelled ANT+ remote connection")
                    disconnect()
                }

                RequestAccessResult.CHANNEL_NOT_AVAILABLE -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_FAILED", "Channel not available")
                    Timber.w("ANT+ channel not available")
                    disconnect()
                }

                RequestAccessResult.OTHER_FAILURE -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_FAILED", "Other failure")
                    Timber.w("ANT+ connection failed")
                    disconnect()
                }

                RequestAccessResult.DEPENDENCY_NOT_INSTALLED -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_FAILED", "Dependency not installed")
                    Timber.w("ANT+ dependency not installed")
                    disconnect()
                }

                RequestAccessResult.DEVICE_ALREADY_IN_USE -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_FAILED", "Device already in use")
                    Timber.w("ANT+ device already in use")
                    disconnect()
                }

                RequestAccessResult.SEARCH_TIMEOUT -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_FAILED", "Search timeout")
                    Timber.w("ANT+ search timed out")
                    disconnect()
                }

                RequestAccessResult.ALREADY_SUBSCRIBED -> {
                    _isConnected = true
                    // OPTION B: Notify real ANT+ connection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, true)
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_SUCCESS", "Already subscribed")
                    Timber.d("ANT+ already subscribed")
                }

                RequestAccessResult.BAD_PARAMS -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_FAILED", "Bad parameters")
                    Timber.w("ANT+ bad parameters")
                    disconnect()
                }

                RequestAccessResult.ADAPTER_NOT_DETECTED -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_FAILED", "Adapter not detected")
                    Timber.w("ANT+ adapter not detected")
                    disconnect()
                }

                RequestAccessResult.UNRECOGNIZED -> {
                    _isConnected = false
                    DebugLogger.logConnectionEvent(deviceNumber, "CONNECTION_FAILED", "Unrecognized result")
                    Timber.w("ANT+ unrecognized result")
                    disconnect()
                }
            }

        }

    fun isConnectedToDevice(deviceNumber: Int): Boolean {
        return _isConnected && remotePcc?.antDeviceNumber == deviceNumber
    }
    fun setupCommandCallback(callback: (AntRemoteKey, PressType) -> Unit) {
        Timber.d("Setting up callback for ANT+ commands")
        this.commandCallback = callback
    }

    fun setLearningMode(enabled: Boolean) {
        _learningMode = enabled
        Timber.d("Learning mode: $_learningMode")
    }


    private val mRemoteCommand =
        AntPlusGenericControllableDevicePcc.IGenericCommandReceiver { _, _, _, _, _, commandNumber ->
            try {
                val deviceNumber = remotePcc?.antDeviceNumber ?: 0

                // Record activity ANTES del throttle: el HeartbeatManager / verificador
                // de conexión usa deviceActivityCache para saber que el mando sigue vivo.
                // Si solo registramos en eventos no-throttled, los press-and-hold rápidos
                // del ANT+ no alimentan el heartbeat → el sistema cree que el mando está
                // inactivo y dispara verificaciones que pueden glitch'ear pulsaciones.
                PerformanceOptimizer.recordDeviceActivity(deviceNumber)

                // ── Inline throttle PER-DEVICE (no coroutine alloc en hot path) ────────
                val now = SystemClock.elapsedRealtime()
                val last = lastCommandTimeByDevice[deviceNumber] ?: 0L
                if (now - last < COMMAND_PROCESSING_DELAY_MS) {
                    return@IGenericCommandReceiver CommandStatus.PASS
                }
                lastCommandTimeByDevice[deviceNumber] = now

                if (DebugLogger.isEnabled()) {
                    val antCommand = AntRemoteKey.byCommand[commandNumber]
                    val commandName = antCommand?.getLabelString(context) ?: "UNKNOWN_$commandNumber"
                    DebugLogger.logKeyEvent(deviceNumber, commandName, "RAW", true)
                    Timber.d("[ANT] Command received: $commandNumber (Learning mode: $learningMode)")
                }

                if (learningMode) {
                    // Learning mode: direct callback (non-blocking – will launch IO coroutine in KremoteExtension)
                    AntRemoteKey.byCommand[commandNumber]?.let {
                        commandCallback.invoke(it, PressType.SINGLE)
                    }
                } else {
                    // Normal mode: posts to Main looper (non-blocking, DoubleTapDetector is thread-safe)
                    doubleTapDetector?.handleCommand(commandNumber)
                }

                CommandStatus.PASS
            } catch (e: Exception) {
                Timber.e(e, "Error processing ANT+ command")
                CommandStatus.FAIL
            }
        }


    private val mRemoteDeviceStateChangeReceiver =
        AntPluginPcc.IDeviceStateChangeReceiver { newDeviceState: DeviceState ->
            val deviceNumber = remotePcc?.antDeviceNumber ?: 0

            when (newDeviceState) {
                DeviceState.DEAD -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "DEVICE_STATE_DEAD", "Connection lost")
                    Timber.d("ANT+ remote connection dead")
                    disconnect()
                }

                DeviceState.CLOSED -> {
                    _isConnected = false
                    // OPTION B: Notify real ANT+ disconnection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    DebugLogger.logConnectionEvent(deviceNumber, "DEVICE_STATE_CLOSED", "Connection closed")
                    Timber.d("ANT+ remote connection closed")
                }

                DeviceState.TRACKING -> {
                    _isConnected = true
                    // OPTION B: Notify real ANT+ connection event
                    PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, true)
                    DebugLogger.logConnectionEvent(deviceNumber, "DEVICE_STATE_TRACKING", "Device tracking and ready")
                    Timber.d("ANT+ remote connected and tracking")
                }

                else -> {
                    DebugLogger.logConnectionEvent(deviceNumber, "DEVICE_STATE_CHANGE", "State: $newDeviceState")
                    Timber.d("ANT+ remote state changed: $newDeviceState")
                    // SEARCHING y demás estados no-terminales NO son conexión activa: solo
                    // TRACKING entrega datos. Reportarlos como conectados marcaba falsos
                    // positivos (cancelaba reconexiones en curso al "recuperar" una conexión
                    // que en realidad seguía buscando).
                    if (_isConnected) {
                        _isConnected = false
                        PerformanceOptimizer.notifyAntConnectionStateChanged(deviceNumber, false)
                    }
                }
            }
        }

    fun updateDoubleTapTimeout(timeout: Long) {
        if (this.doubleTapTimeout_field == timeout) return
        this.doubleTapTimeout_field = timeout
        doubleTapDetector?.updateTimeout(timeout) ?: run {
            doubleTapDetector = DoubleTapDetector(timeout, doubleTapEnabled) { commandNumber, pressType ->
                AntRemoteKey.byCommand[commandNumber]?.let {
                    if (DebugLogger.isEnabled()) {
                        Timber.d("[ANT] Processing command: ${it.getLabelString(context)} (${if(pressType == PressType.DOUBLE) "DOUBLE" else "SINGLE"})")
                    }
                    commandCallback.invoke(it, pressType)
                }
            }
        }
    }

    fun updateDoubleTapEnabled(enabled: Boolean) {
        if (doubleTapEnabled == enabled) return
        doubleTapEnabled = enabled
        doubleTapDetector?.updateEnabled(enabled) ?: run {
            doubleTapDetector = DoubleTapDetector(doubleTapTimeout_field, enabled) { commandNumber, pressType ->
                AntRemoteKey.byCommand[commandNumber]?.let {
                    commandCallback.invoke(it, pressType)
                }
            }
        }
    }

    fun connect(deviceNumber: Int) {

        if (isConnecting) {
            Timber.d("[ANT] Connection attempt already in progress")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastConnectionAttempt < minReconnectInterval) {
            Timber.d("[ANT] Reconnection attempt too frequent, ignoring")
            return
        }

        lastConnectionAttempt = now
        isConnecting = true


        runBlocking(Dispatchers.Main) {
            try {
                Timber.d("[ANT] Connecting to device #$deviceNumber (Learning mode: $learningMode)")


                val currentLearningMode = learningMode

                if (_isConnected && remotePcc?.antDeviceNumber != deviceNumber) {
                    disconnect()
                } else if (_isConnected && remotePcc?.antDeviceNumber == deviceNumber) {
                    Timber.d("[ANT] Already connected to device $deviceNumber")
                    isConnecting = false
                    return@runBlocking
                }

                _learningMode = currentLearningMode

                remoteReleaseHandle = AntPlusGenericControllableDevicePcc.requestAccess(
                    context,
                    mRemoteResultReceiver,
                    mRemoteDeviceStateChangeReceiver,
                    mRemoteCommand,
                    deviceNumber
                )

                Timber.d("[ANT] Connection requested to device #$deviceNumber")
            } catch (e: Exception) {
                _isConnected = false
                Timber.e(e, "[ANT] Error connecting to device")
            } finally {
                isConnecting = false
            }
        }
    }

    fun disconnect() {
        val wasLearning = _learningMode
        Timber.d("Closing ANT+ remote handler (Learning mode: $_learningMode)")
        remoteReleaseHandle?.close()
        remoteReleaseHandle = null
        remotePcc = null
        _isConnected = false

        if (wasLearning) {
            Timber.d("[ANT] Preserving learning mode: true")
            _learningMode = true
        }
    }

    fun stopScan(disconnect: Boolean = false) {
        Timber.d("Stopping ANT+ device search (disconnect=$disconnect)")
        try {
            _detectedDevices.value = emptyList()
            if (disconnect) {
                disconnect()
                _isConnected = false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping ANT+ device search")
            throw e
        }
    }

   fun startDeviceSearch() {
        Timber.d("Starting ANT+ device search")
        try {

            runBlocking(Dispatchers.Main) {

                disconnect()

                remoteReleaseHandle = AntPlusGenericControllableDevicePcc.requestAccess(
                    context,
                    mRemoteResultReceiver,
                    mRemoteDeviceStateChangeReceiver,
                    mRemoteCommand,
                    0 // DeviceNumber 0 para buscar cualquier dispositivo
                )

                Timber.d("ANT+ device search started successfully remoteReleaseHandle: $remoteReleaseHandle channel: $remotePcc")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting ANT+ device search")
            throw e
        }
    }


    fun cleanup() {
        try {
            remoteReleaseHandle?.close()
            remoteReleaseHandle = null
            Timber.d("ANT+ cleanup completed")
        } catch (e: Exception) {
            Timber.e(e, "Error in ANT+ cleanup")
        }
    }
}