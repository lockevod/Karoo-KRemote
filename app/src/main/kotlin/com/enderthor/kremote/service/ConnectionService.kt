package com.enderthor.kremote.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.extension.KremoteExtension
import com.enderthor.kremote.data.autoReconnect
import com.enderthor.kremote.data.DEBUG_LOGGING_ENABLED
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.ReconnectionManagerSingleton
import com.enderthor.kremote.utils.PerformanceOptimizer
import com.enderthor.kremote.utils.HeartbeatManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class ConnectionService : Service() {
    private lateinit var repository: RemoteRepository
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Timber.d("[ConnectionService] onCreate")

        // Inicializar sistema de debug logging
        DebugLogger.initialize(applicationContext, DEBUG_LOGGING_ENABLED)
        DebugLogger.logConnectionEvent(0, "SERVICE_CREATED", "ConnectionService initialized")

        // Inicializar PerformanceOptimizer
        PerformanceOptimizer.setOptimizationEnabled(true)

        repository = RemoteRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.logConnectionEvent(0, "SERVICE_STARTED", "ConnectionService started")
        Timber.d("[ConnectionService] 🚀 Servicio iniciado")

        val kremoteExtension = KremoteExtension.getInstance()
        if (kremoteExtension == null) {
            DebugLogger.logError("SERVICE", "KremoteExtension no disponible")
            Timber.e("[ConnectionService] KremoteExtension no disponible")
            stopSelf()
            return START_NOT_STICKY
        }

        // NUEVO: Obtener SharedPreferences para configuración
        val sharedPrefs = getSharedPreferences("app_preferences", MODE_PRIVATE)

        // Inicializar el gestor de reconexión usando el singleton
        val reconnectionManager = ReconnectionManagerSingleton.initialize(kremoteExtension.antManager, serviceScope)

        // DIAGNÓSTICO EXTENDIDO: Verificar inicialización completa
        DebugLogger.logConnectionEvent(0, "SINGLETON_INITIALIZED", "ReconnectionManager singleton created", "ConnectionService")
        DebugLogger.logConnectionEvent(0, "SERVICE_START_COMMAND", "ConnectionService onStartCommand ejecutado - intent: ${intent?.action}, flags: $flags, startId: $startId", "ConnectionService")
        Timber.d("[ConnectionService] 🚀 ReconnectionManager singleton inicializado correctamente")
        Timber.d("[ConnectionService] 📱 Servicio iniciado con intent: ${intent?.action}")

        job = serviceScope.launch {
            try {
                DebugLogger.logConnectionEvent(0, "SERVICE_JOB_STARTED", "Job principal del servicio iniciado", "ConnectionService")

                // Usar throttling para cargar configuración
                PerformanceOptimizer.throttledExecution("load_config", 1000L) {
                    DebugLogger.logConnectionEvent(0, "LOADING_CONFIG", "Cargando configuración de dispositivos", "ConnectionService")
                    val config = repository.currentConfig.first()
                    val activeDevices = config.devices.filter { it.isActive }

                    DebugLogger.logConnectionEvent(0, "ACTIVE_DEVICES_FOUND", "Count: ${activeDevices.size}, devices: ${activeDevices.map { "${it.name}(#${it.antDeviceId})" }}", "ConnectionService")
                    Timber.d("[ConnectionService] 📋 Dispositivos activos encontrados: ${activeDevices.size}")
                    activeDevices.forEach { device ->
                        Timber.d("[ConnectionService] - ${device.name} (ANT ID: ${device.antDeviceId})")
                    }

                    if (activeDevices.isEmpty()) {
                        DebugLogger.logConnectionEvent(0, "NO_ACTIVE_DEVICES", "No active devices found - monitoring will not start", "ConnectionService")
                        Timber.w("[ConnectionService] ⚠️ No hay dispositivos activos - el monitoreo no se iniciará")
                        return@throttledExecution
                    }

                    // NUEVO: Verificar configuración de reconexión automática
                    val autoReconnect = sharedPrefs.getBoolean("auto_reconnect", autoReconnect)
                    DebugLogger.logConnectionEvent(0, "AUTO_RECONNECT_CONFIG", "autoReconnect: $autoReconnect (default: ${true})", "ConnectionService")
                    Timber.d("[ConnectionService] ⚙️ Configuración autoReconnect: $autoReconnect")

                    activeDevices.forEach { device ->
                        device.macAddress?.toInt()?.let { deviceId ->
                            try {
                                DebugLogger.logConnectionEvent(deviceId, "CONNECTING", "Initial connection attempt")
                                Timber.d("[ConnectionService] Conectando a dispositivo ANT+ #$deviceId")

                                // Usar throttling para conexiones secuenciales
                                PerformanceOptimizer.throttledExecution(
                                    key = "connect_$deviceId",
                                    minIntervalMs = 2000L
                                ) {
                                    DebugLogger.logConnectionEvent(deviceId, "ANT_CONNECT_START", "Calling antManager.connect($deviceId)", "ConnectionService")
                                    kremoteExtension.antManager.connect(deviceId)
                                    DebugLogger.logConnectionEvent(deviceId, "ANT_CONNECT_COMPLETE", "antManager.connect() completed", "ConnectionService")

                                    if (autoReconnect) {
                                        // Usar el nuevo sistema de reconexión mejorado
                                        DebugLogger.logConnectionEvent(deviceId, "STARTING_MONITORING", "Initiating device monitoring with ReconnectionManager", "ConnectionService")
                                        reconnectionManager.startMonitoring(deviceId)
                                        DebugLogger.logConnectionEvent(deviceId, "MONITORING_ACTIVE", "Device monitoring started successfully", "ConnectionService")
                                        Timber.d("[ConnectionService] Monitoreo iniciado para dispositivo #$deviceId")
                                    } else {
                                        DebugLogger.logConnectionEvent(deviceId, "MONITORING_DISABLED", "autoReconnect is false - monitoring skipped", "ConnectionService")
                                    }
                                }
                            } catch (e: Exception) {
                                DebugLogger.logError("CONNECTION", "Error conectando a ANT+ #$deviceId", e)
                                Timber.e(e, "[ConnectionService] Error conectando a ANT+ #$deviceId")
                            }
                        } ?: run {
                            DebugLogger.logError("CONFIGURATION", "Dispositivo sin dirección MAC: ${device.name}")
                            Timber.w("[ConnectionService] Dispositivo sin dirección MAC")
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLogger.logError("SERVICE", "Error iniciando conexiones", e)
                Timber.e(e, "[ConnectionService] Error iniciando conexiones")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        DebugLogger.logConnectionEvent(0, "SERVICE_DESTROYED", "ConnectionService stopping")
        Timber.d("[ConnectionService] onDestroy")

        job?.cancel()

        // MEJORADO: Usar HeartbeatManager.cleanup() en lugar de solo clearHeartbeatCaches()
        HeartbeatManager.cleanup()

        // Destruir el singleton del ReconnectionManager
        ReconnectionManagerSingleton.destroy()

        // Limpiar cachés del PerformanceOptimizer
        PerformanceOptimizer.clearCaches()

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}