package com.enderthor.kremote.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.enderthor.kremote.bluetooth.BluetoothManager
import com.enderthor.kremote.bluetooth.BluetoothService
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.extension.KremoteExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class ConnectionService : Service() {
    private lateinit var repository: RemoteRepository
    private lateinit var bluetoothManager: BluetoothManager
    private var currentBluetoothService: BluetoothService? = null
    private var reconnectJob: Job? = null
    private var antReconnectJob: Job? = null
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // Obtener la instancia de AntManager desde KremoteExtension


    override fun onCreate() {
        super.onCreate()
        Timber.d("ConnectionService onCreate")

        repository = RemoteRepository(applicationContext)
        bluetoothManager = BluetoothManager(applicationContext)
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
            try {
                repository.currentConfig.collect { config ->
                    val activeDevice = config.devices.find { it.isActive }
                    if (activeDevice != null && config.globalSettings.autoReconnect) {
                        when (activeDevice.type) {
                            RemoteType.BLUETOOTH -> {
                                monitorBluetoothConnection(
                                    activeDevice.macAddress,
                                    config.globalSettings.reconnectAttempts,
                                    config.globalSettings.reconnectDelayMs
                                )
                            }
                            RemoteType.ANT -> {
                                monitorAntConnection(
                                    activeDevice.macAddress?.toInt(),
                                    config.globalSettings.reconnectAttempts,
                                    config.globalSettings.reconnectDelayMs
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error monitoring connections")
            }
        }
    }

    private fun monitorBluetoothConnection(
        macAddress: String?,
        maxAttempts: Int,
        delayMs: Long
    ) {
        if (macAddress == null) {
            Timber.w("No MAC address available for Bluetooth device")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            var attempts = 0
            while (attempts < maxAttempts) {
                if (currentBluetoothService?.connectionState?.value == BluetoothService.ConnectionState.CONNECTED) {
                    attempts = 0
                } else {
                    Timber.d("Attempting Bluetooth reconnection, attempt ${attempts + 1} of $maxAttempts")
                    try {
                        val btDevice = bluetoothManager.getBluetoothDeviceByAddress(macAddress)
                        currentBluetoothService = bluetoothManager.createBluetoothService { /* No necesitamos manejar keypresses aquí */ }
                        btDevice?.let { device ->
                            currentBluetoothService?.connect(device)
                            if (currentBluetoothService?.connectionState?.value == BluetoothService.ConnectionState.CONNECTED) {
                                Timber.d("Bluetooth reconnection successful")
                                attempts = 0
                            } else {
                                attempts++
                            }
                        } ?: run {
                            attempts++
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error during Bluetooth reconnection attempt")
                        attempts++
                    }
                }
                delay(delayMs)
            }
            Timber.w("Max Bluetooth reconnection attempts reached")
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
                    val kremoteExtension = KremoteExtension.getInstance()
                    if (kremoteExtension?.isAntConnected() == true) {
                        attempts = 0
                        delay(1000)
                        continue
                    }

                    Timber.d("Intento de reconexión ANT+ ${attempts + 1} de $maxAttempts para dispositivo #$deviceNumber")
                    try {
                        kremoteExtension?.connectAntDevice(deviceNumber)

                        // Esperar a que la conexión se establezca
                        var checkAttempts = 0
                        while (kremoteExtension?.isAntConnected() != true && checkAttempts < 5) {
                            delay(1000)
                            checkAttempts++
                        }

                        if (kremoteExtension?.isAntConnected() == true) {
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
                    when (device.type) {
                        RemoteType.ANT -> {
                            device.macAddress?.let { mac ->
                                kremoteExtension.connectAntDevice(mac.toInt())
                            }
                        }
                        RemoteType.BLUETOOTH -> {
                            // ... manejo de Bluetooth ...
                        }
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
            currentBluetoothService?.disconnect()
        } catch (e: Exception) {
            Timber.e(e, "Error during service destruction")
        }
        super.onDestroy()
    }
}