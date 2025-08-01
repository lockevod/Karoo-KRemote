package com.enderthor.kremote.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.Gravity
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup

import android.graphics.drawable.GradientDrawable
import com.enderthor.kremote.R
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.extension.KremoteExtension
import com.enderthor.kremote.data.autoReconnect
import com.enderthor.kremote.data.DEBUG_LOGGING_ENABLED
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.utils.ReconnectionManagerSingleton
import com.enderthor.kremote.utils.PerformanceOptimizer
import com.enderthor.kremote.utils.HeartbeatManager
import com.enderthor.kremote.utils.NotificationHelper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.core.graphics.toColorInt


class ConnectionService : Service() {
    private lateinit var repository: RemoteRepository
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isFromExtension = false

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

        // NUEVO: Detectar si el servicio se inicia desde la extensión ANTES
        isFromExtension = intent?.getBooleanExtra("is_extension", false) ?: false
        DebugLogger.logConnectionEvent(0, "SERVICE_MODE", "Service started from ${if (isFromExtension) "extension" else "app"}")
        Timber.d("[ConnectionService] 🔧 Modo: ${if (isFromExtension) "Extensión" else "App"}")

        // CRÍTICO: Verificar estado de la extensión una sola vez
        val kremoteExtension = KremoteExtension.getInstance()
        val extensionAvailable = kremoteExtension != null

        DebugLogger.logConnectionEvent(0, "EXTENSION_CHECK", "KremoteExtension available: $extensionAvailable")

        // CRÍTICO: Siempre iniciar como foreground, pero con mensaje apropiado
        startForegroundServiceWithStatus(extensionAvailable)

        if (!extensionAvailable) {
            DebugLogger.logError("SERVICE", "KremoteExtension no disponible - servicio iniciado solo para mostrar estado")
            Timber.e("[ConnectionService] KremoteExtension no disponible - reinicia el Karoo")

            // NUEVO: Mostrar Toast visible en Karoo cuando la extensión no esté disponible
            showExtensionNotAvailableToast()

            // No detener el servicio, mantenerlo para mostrar el estado al usuario
            return START_STICKY
        }

        // Si es desde la extensión, convertir a servicio normal después de un delay
        if (isFromExtension) {
            serviceScope.launch {
                kotlinx.coroutines.delay(1000) // Esperar un segundo
                stopForeground(STOP_FOREGROUND_REMOVE) // Quitar la notificación pero mantener el servicio
                DebugLogger.logConnectionEvent(0, "CONVERTED_TO_BACKGROUND", "Service converted from foreground to background (extension mode)")
                Timber.d("[ConnectionService] Servicio convertido a segundo plano (modo extensión)")
            }
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

    private fun startForegroundServiceWithStatus(extensionAvailable: Boolean) {
        try {
            val title: String
            val content: String
            val isWarning: Boolean

            if (extensionAvailable) {
                title = if (isFromExtension) "KRemote - Extensión" else "KRemote - App"
                content = if (isFromExtension) {
                    "Monitoreo desde extensión Karoo"
                } else {
                    "Servicio de conexión ANT+ activo"
                }
                isWarning = false
            } else {
                title = "KRemote - ⚠️ Problema"
                content = "Extensión no disponible"
                isWarning = true
            }

            // Crear notificación con el tipo apropiado
            val notification = NotificationHelper.createServiceNotification(
                applicationContext,
                title,
                content,
                isWarning
            )

            // Iniciar el servicio en primer plano con la notificación
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)

            DebugLogger.logConnectionEvent(0, "FOREGROUND_SERVICE_STARTED", "Service started in foreground mode (warning: $isWarning)")
            Timber.d("[ConnectionService] Servicio en primer plano iniciado ${if (isWarning) "CON ADVERTENCIA" else "normalmente"}")
        } catch (e: Exception) {
            DebugLogger.logError("SERVICE", "Error starting foreground service", e)
            Timber.e(e, "[ConnectionService] Error iniciando servicio en primer plano")
        }
    }

    private fun showExtensionNotAvailableToast() {
        // Usar string resource con placeholders en lugar de concatenación
        val message = getString(
            R.string.extension_warning_message,
            getString(R.string.extension_not_available),
            getString(R.string.extension_restart_karoo)
        )

        // Crear overlay personalizado que aparece ARRIBA usando WindowManager
        Handler(Looper.getMainLooper()).post {
            try {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

                // Crear TextView personalizado
                val overlayView = TextView(applicationContext).apply {
                    text = message
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(40, 20, 40, 20)
                    gravity = Gravity.CENTER

                    // Crear fondo redondeado rojo
                    val drawable = GradientDrawable().apply {
                        setColor("#D32F2F".toColorInt()) // Rojo material
                        cornerRadius = 16f
                        setStroke(4, "#B71C1C".toColorInt()) // Borde más oscuro
                    }
                    background = drawable
                }

                // Parámetros para posicionar el overlay ARRIBA
                val layoutParams = WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 50 // 50px desde arriba
                }

                // Añadir el overlay a la pantalla
                windowManager.addView(overlayView, layoutParams)

                // Remover el overlay después de 7 segundos
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        windowManager.removeView(overlayView)
                    } catch (e: Exception) {
                        Timber.d("[ConnectionService] Overlay ya removido: ${e.message}")
                    }
                }, 7000)

                Timber.d("[ConnectionService] Overlay de advertencia mostrado arriba")

            } catch (e: Exception) {
                Timber.e(e, "[ConnectionService] Error mostrando overlay - fallback a Toast")
                // Fallback a Toast si el overlay falla
                Toast.makeText(applicationContext, "⚠️ $message", Toast.LENGTH_LONG).show()
            }
        }
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

