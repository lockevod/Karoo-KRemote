package com.enderthor.kremote.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.extension.KremoteExtension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class ConnectionService : Service() {
    private lateinit var repository: RemoteRepository

    private var reconnectJob: Job? = null
    private var antReconnectJob: Job? = null
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Obtener la instancia de AntManager desde KremoteExtension


    override fun onCreate() {
        super.onCreate()
        Timber.d("ConnectionService onCreate")

        repository = RemoteRepository(applicationContext)
        startConnectionMonitoring()
    }

    private fun startConnectionMonitoring() {
        serviceScope.launch {
            try {
                val config = repository.currentConfig.first()
                if (config.globalSettings.autoReconnect) {
                    monitorConnections()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error starting connection monitoring")
            }
        }
    }

    private fun monitorConnections() {
        serviceScope.launch {
            repository.currentConfig
                .collect { config ->
                    try {
                        val activeDevice = config.devices.find { it.isActive }
                        if (activeDevice != null && config.globalSettings.autoReconnect) {
                            monitorAntConnection(
                                activeDevice.macAddress?.toInt(),
                                config.globalSettings.reconnectAttempts,
                                config.globalSettings.reconnectDelayMs
                            )
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error monitorizando conexiones")
                    }
                }
        }
    }



    private fun monitorAntConnection(
        deviceNumber: Int?,
        maxAttempts: Int,
        delayMs: Long
    ) {
        if (deviceNumber == null) {
            Timber.w("No se encontró número de dispositivo ANT+")
            return
        }

        antReconnectJob?.cancel()
        antReconnectJob = serviceScope.launch {
            var attempts = 0
            try {
                while (attempts < maxAttempts) {
                    // Obtener la instancia de KremoteExtension
                    val kremoteExtension = KremoteExtension.getInstance()

                    // Verificar si el dispositivo está conectado usando antManager
                    if (kremoteExtension?.antManager?.isConnected == true) {
                        attempts = 0
                        delay(1000)
                        continue
                    }

                    Timber.d("Intento de reconexión ANT+ ${attempts + 1} de $maxAttempts para dispositivo #$deviceNumber")
                    try {
                        // Usar antManager directamente para conectar
                        kremoteExtension?.antManager?.connect(deviceNumber)

                        // Esperar a que la conexión se establezca
                        var checkAttempts = 0
                        while (kremoteExtension?.antManager?.isConnected != true && checkAttempts < 5) {
                            delay(1000)
                            checkAttempts++
                        }

                        // Verificar si la conexión fue exitosa usando antManager
                        if (kremoteExtension?.antManager?.isConnected == true) {
                            Timber.d("Reconexión ANT+ exitosa para dispositivo #$deviceNumber")
                            attempts = 0
                        } else {
                            Timber.d("Falló la reconexión ANT+ para dispositivo #$deviceNumber")
                            attempts++
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error durante intento de reconexión ANT+")
                        attempts++
                    }

                    delay(delayMs)
                }
                Timber.w("Se alcanzó el máximo de intentos de reconexión ANT+")
            } catch (e: Exception) {
                Timber.e(e, "Error en el monitor de conexión ANT+")
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("[ConnectionService] Servicio iniciado")

        val kremoteExtension = KremoteExtension.getInstance()
        if (kremoteExtension == null) {
            Timber.e("[ConnectionService] KremoteExtension no está disponible")
            stopSelf()
            return START_NOT_STICKY
        }

        job = serviceScope.launch {
            try {
                val config = repository.currentConfig.first()
                config.devices.filter { it.isActive }.forEach { device ->
                    device.macAddress?.let { mac ->
                        // Usar antManager directamente
                        kremoteExtension.antManager.connect(mac.toInt())
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[ConnectionService] Error iniciando conexiones")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.d("ConnectionService onDestroy")
        try {
            reconnectJob?.cancel()
            antReconnectJob?.cancel()
            serviceScope.cancel()
        } catch (e: Exception) {
            Timber.e(e, "Error during service destruction")
        }
        super.onDestroy()
    }
}