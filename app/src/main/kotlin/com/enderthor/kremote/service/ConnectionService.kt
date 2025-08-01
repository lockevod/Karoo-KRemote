package com.enderthor.kremote.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import android.os.Handler
import android.os.Looper

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


class ConnectionService : Service() {
    private lateinit var repository: RemoteRepository
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isFromExtension = false

    override fun onCreate() {
        super.onCreate()
        Timber.d("[ConnectionService] onCreate")

        // Initialize debug logging system
        DebugLogger.initialize(applicationContext, DEBUG_LOGGING_ENABLED)
        DebugLogger.logConnectionEvent(0, "SERVICE_CREATED", "ConnectionService initialized")

        // Initialize PerformanceOptimizer
        PerformanceOptimizer.setOptimizationEnabled(true)

        repository = RemoteRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.logConnectionEvent(0, "SERVICE_STARTED", "ConnectionService started")
        Timber.d("[ConnectionService] 🚀 Service started")

        // NEW: Detect if the service starts from the extension BEFORE
        isFromExtension = intent?.getBooleanExtra("is_extension", false) ?: false
        DebugLogger.logConnectionEvent(0, "SERVICE_MODE", "Service started from ${if (isFromExtension) "extension" else "app"}")
        Timber.d("[ConnectionService] 🔧 Mode: ${if (isFromExtension) "Extension" else "App"}")

        // CRITICAL: Check extension state only once
        val kremoteExtension = KremoteExtension.getInstance()
        val extensionAvailable = kremoteExtension != null

        DebugLogger.logConnectionEvent(0, "EXTENSION_CHECK", "KremoteExtension available: $extensionAvailable")

        // CRITICAL: Always start as foreground, but with appropriate message
        startForegroundServiceWithStatus(extensionAvailable)

        if (!extensionAvailable) {
            DebugLogger.logError("SERVICE", "KremoteExtension not available - service started only to show status")
            Timber.e("[ConnectionService] KremoteExtension not available - restart your Karoo")

            // NEW: Show visible Toast on Karoo when extension is not available
            showExtensionNotAvailableToast()

            // Don't stop the service, keep it to show status to user
            return START_STICKY
        }

        // If from extension, convert to normal service after a delay
        if (isFromExtension) {
            serviceScope.launch {
                kotlinx.coroutines.delay(1000) // Wait one second
                stopForeground(STOP_FOREGROUND_REMOVE) // Remove notification but keep service
                DebugLogger.logConnectionEvent(0, "CONVERTED_TO_BACKGROUND", "Service converted from foreground to background (extension mode)")
                Timber.d("[ConnectionService] Service converted to background (extension mode)")
            }
        }

        // NEW: Get SharedPreferences for configuration
        val sharedPrefs = getSharedPreferences("app_preferences", MODE_PRIVATE)

        // Initialize reconnection manager using singleton
        val reconnectionManager = ReconnectionManagerSingleton.initialize(kremoteExtension.antManager, serviceScope)

        // EXTENDED DIAGNOSTICS: Check complete initialization
        DebugLogger.logConnectionEvent(0, "SINGLETON_INITIALIZED", "ReconnectionManager singleton created", "ConnectionService")
        DebugLogger.logConnectionEvent(0, "SERVICE_START_COMMAND", "ConnectionService onStartCommand executed - intent: ${intent?.action}, flags: $flags, startId: $startId", "ConnectionService")
        Timber.d("[ConnectionService] 🚀 ReconnectionManager singleton initialized correctly")
        Timber.d("[ConnectionService] 📱 Service started with intent: ${intent?.action}")

        job = serviceScope.launch {
            try {
                DebugLogger.logConnectionEvent(0, "SERVICE_JOB_STARTED", "Main service job started", "ConnectionService")

                // Use throttling to load configuration
                PerformanceOptimizer.throttledExecution("load_config", 1000L) {
                    DebugLogger.logConnectionEvent(0, "LOADING_CONFIG", "Loading device configuration", "ConnectionService")
                    val config = repository.currentConfig.first()
                    val activeDevices = config.devices.filter { it.isActive }

                    DebugLogger.logConnectionEvent(0, "ACTIVE_DEVICES_FOUND", "Count: ${activeDevices.size}, devices: ${activeDevices.map { "${it.name}(#${it.antDeviceId})" }}", "ConnectionService")
                    Timber.d("[ConnectionService] 📋 Active devices found: ${activeDevices.size}")
                    activeDevices.forEach { device ->
                        Timber.d("[ConnectionService] - ${device.name} (ANT ID: ${device.antDeviceId})")
                    }

                    if (activeDevices.isEmpty()) {
                        DebugLogger.logConnectionEvent(0, "NO_ACTIVE_DEVICES", "No active devices found - monitoring will not start", "ConnectionService")
                        Timber.w("[ConnectionService] ⚠️ No active devices - monitoring will not start")
                        return@throttledExecution
                    }

                    // NEW: Check automatic reconnection configuration
                    val autoReconnect = sharedPrefs.getBoolean("auto_reconnect", autoReconnect)
                    DebugLogger.logConnectionEvent(0, "AUTO_RECONNECT_CONFIG", "autoReconnect: $autoReconnect (default: ${true})", "ConnectionService")
                    Timber.d("[ConnectionService] ⚙️ AutoReconnect configuration: $autoReconnect")

                    activeDevices.forEach { device ->
                        device.macAddress?.toInt()?.let { deviceId ->
                            try {
                                DebugLogger.logConnectionEvent(deviceId, "CONNECTING", "Initial connection attempt")
                                Timber.d("[ConnectionService] Connecting to ANT+ device #$deviceId")

                                // Usar throttling to secuential connection attempts
                                PerformanceOptimizer.throttledExecution(
                                    key = "connect_$deviceId",
                                    minIntervalMs = 2000L
                                ) {
                                    DebugLogger.logConnectionEvent(deviceId, "ANT_CONNECT_START", "Calling antManager.connect($deviceId)", "ConnectionService")
                                    kremoteExtension.antManager.connect(deviceId)
                                    DebugLogger.logConnectionEvent(deviceId, "ANT_CONNECT_COMPLETE", "antManager.connect() completed", "ConnectionService")

                                    if (autoReconnect) {
                                        // Use the new improved reconnection system
                                        DebugLogger.logConnectionEvent(deviceId, "STARTING_MONITORING", "Initiating device monitoring with ReconnectionManager", "ConnectionService")
                                        reconnectionManager.startMonitoring(deviceId)
                                        DebugLogger.logConnectionEvent(deviceId, "MONITORING_ACTIVE", "Device monitoring started successfully", "ConnectionService")
                                        Timber.d("[ConnectionService] Monitoring started for device #$deviceId")
                                    } else {
                                        DebugLogger.logConnectionEvent(deviceId, "MONITORING_DISABLED", "autoReconnect is false - monitoring skipped", "ConnectionService")
                                    }
                                }
                            } catch (e: Exception) {
                                DebugLogger.logError("CONNECTION", "Error connecting to ANT+ #$deviceId", e)
                                Timber.e(e, "[ConnectionService] Error connecting to ANT+ #$deviceId")
                            }
                        } ?: run {
                            DebugLogger.logError("CONFIGURATION", "Device without MAC address: ${device.name}")
                            Timber.w("[ConnectionService] Device without MAC address")
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLogger.logError("SERVICE", "Error starting connections", e)
                Timber.e(e, "[ConnectionService] Error starting connections")
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
                title = if (isFromExtension) "KRemote - Extension" else "KRemote - App"
                content = if (isFromExtension) {
                    "Monitoring from Karoo Exten"
                } else {
                    "ANT+ connection service active"
                }
                isWarning = false
            } else {
                title = "KRemote - ⚠️ Problem"
                content = "Extension not available"
                isWarning = true
            }

            // Create notification with appropriate type
            val notification = NotificationHelper.createServiceNotification(
                applicationContext,
                title,
                content,
                isWarning
            )

            // Start the service in foreground with the notification
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)

            DebugLogger.logConnectionEvent(0, "FOREGROUND_SERVICE_STARTED", "Service started in foreground mode (warning: $isWarning)")
            Timber.d("[ConnectionService] Foreground service started ${if (isWarning) "WITH WARNING" else "normally"}")
        } catch (e: Exception) {
            DebugLogger.logError("SERVICE", "Error starting foreground service", e)
            Timber.e(e, "[ConnectionService] Error starting foreground service")
        }
    }

    private fun showExtensionNotAvailableToast() {
        // Use string resource with placeholders instead of concatenation
        val message = getString(
            R.string.extension_warning_message,
            getString(R.string.extension_not_available),
            getString(R.string.extension_restart_karoo)
        )

        // Use Toast directly - simpler and always works
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "⚠️ $message", Toast.LENGTH_LONG).show()
            Timber.d("[ConnectionService] Warning toast shown: $message")
        }
    }

    override fun onDestroy() {
        DebugLogger.logConnectionEvent(0, "SERVICE_DESTROYED", "ConnectionService stopping")
        Timber.d("[ConnectionService] onDestroy")

        job?.cancel()

        // IMPROVED: Use HeartbeatManager.cleanup() instead of just clearHeartbeatCaches()
        HeartbeatManager.cleanup()

        // Destroy the ReconnectionManager singleton
        ReconnectionManagerSingleton.destroy()

        // Clear PerformanceOptimizer caches
        PerformanceOptimizer.clearCaches()

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
