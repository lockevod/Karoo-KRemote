package com.enderthor.kremote.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.extension.KremoteExtension
import com.enderthor.kremote.data.autoReconnect
import com.enderthor.kremote.data.reconnectDelayMs
import com.enderthor.kremote.data.reconnectAttempts
import com.enderthor.kremote.data.checkIntervalMs
import com.enderthor.kremote.data.maxreconnectDelayMs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.pow
import timber.log.Timber

class ConnectionService : Service() {
    private lateinit var repository: RemoteRepository

    private var reconnectJob: Job? = null
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Timber.d("[ConnectionService] onCreate")
        repository = RemoteRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("[ConnectionService] 🚀 Servicio iniciado")

        val kremoteExtension = KremoteExtension.getInstance()
        if (kremoteExtension == null) {
            Timber.e("[ConnectionService] ❌ KremoteExtension no disponible")
            stopSelf()
            return START_NOT_STICKY
        }

        job = serviceScope.launch {
            try {
                val config = repository.currentConfig.first()
                val activeDevices = config.devices.filter { it.isActive }

                Timber.d("[ConnectionService] Dispositivos activos: ${activeDevices.size}")

                activeDevices.forEach { device ->
                    device.macAddress?.toInt()?.let { deviceId ->
                        try {
                            Timber.d("[ConnectionService] Conectando a dispositivo ANT+ #$deviceId")
                            kremoteExtension.antManager.connect(deviceId)

                            if (autoReconnect) {
                                monitorAntConnection(
                                    deviceId,
                                    kremoteExtension
                                )
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "[ConnectionService] Error conectando a ANT+ #$deviceId")
                        }
                    } ?: Timber.w("[ConnectionService] Dispositivo sin dirección MAC")
                }

                // Iniciar verificación periódica si hay configuración de auto-reconexión
                if (autoReconnect) {
                    startPeriodicConnectionCheck(kremoteExtension)
                }
            } catch (e: Exception) {
                Timber.e(e, "[ConnectionService] Error iniciando conexiones")
            }
        }

        return START_STICKY
    }

    private fun monitorAntConnection(
        deviceNumber: Int,
        extension: KremoteExtension
    ) {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            var attempts = 0
            try {
                while (attempts < reconnectAttempts) {
                    val delayTime = (reconnectDelayMs * (2.0.pow(attempts.toDouble())).toLong()).coerceAtMost(maxreconnectDelayMs)
                    delay(delayTime)

                    if (!extension.antManager.isConnected) {
                        attempts++
                        Timber.d("[ConnectionService] Intento de reconexión ANT+ #$deviceNumber ($attempts/$reconnectAttempts) - Retraso: ${delayTime}ms")
                        extension.antManager.connect(deviceNumber)
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[ConnectionService] Error en reconexión ANT+")
            }
        }
    }

   private fun startPeriodicConnectionCheck(extension: KremoteExtension) {
        serviceScope.launch {
            var stableConnection = true
            while (true) {
                try {
                    val delayTime = if (stableConnection) checkIntervalMs else checkIntervalMs / 2
                    delay(delayTime)

                    val device = repository.getActiveDevice().first()
                    if (device != null && !extension.antManager.isConnected) {
                        stableConnection = false
                        Timber.d("[ConnectionService] 🔄 Reconexión periódica")
                        device.macAddress?.toInt()?.let { deviceId ->
                            extension.antManager.connect(deviceId)
                        }
                    } else {
                        stableConnection = true
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[ConnectionService] Error en verificación periódica")
                }
            }
        }
    }

    override fun onDestroy() {
        Timber.d("[ConnectionService] onDestroy")
        job?.cancel()
        reconnectJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}