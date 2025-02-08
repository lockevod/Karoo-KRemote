package com.enderthor.kremote.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.enderthor.kremote.bluetooth.BluetoothManager
import com.enderthor.kremote.bluetooth.BluetoothService
import com.enderthor.kremote.ant.AntManager
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteType
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
    private lateinit var antManager: AntManager
    private var serviceScope: CoroutineScope? = null

    private var currentBluetoothService: BluetoothService? = null
    private var reconnectJob: Job? = null
    private var antReconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("ConnectionService onCreate")

        repository = RemoteRepository(applicationContext)
        bluetoothManager = BluetoothManager(applicationContext)
        antManager = AntManager(applicationContext) { /* No necesitamos manejar comandos aquí */ }
        serviceScope = CoroutineScope(Dispatchers.IO + Job())

        startConnectionMonitoring()
    }

    private fun startConnectionMonitoring() {
        serviceScope?.launch {
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
        serviceScope?.launch {
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
        reconnectJob = serviceScope?.launch {
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
        maxAttempts: Int,
        delayMs: Long
    ) {
        antReconnectJob?.cancel()
        antReconnectJob = serviceScope?.launch {
            var attempts = 0
            while (attempts < maxAttempts) {
                if (antManager.isConnected) {
                    attempts = 0
                    delay(1000) // Check connection status every second
                    continue
                }

                Timber.d("Attempting ANT+ reconnection, attempt ${attempts + 1} of $maxAttempts")
                try {
                    antManager.disconnect() // Asegurarse de que no hay conexión anterior
                    antManager.connect()
                    // Esperar un poco para que la conexión se establezca
                    delay(1000)
                    // La conexión exitosa se manejará a través del callback en AntManager
                    // que actualizará isConnected
                } catch (e: Exception) {
                    Timber.e(e, "Error during ANT+ reconnection attempt")
                }

                attempts++
                delay(delayMs)
            }
            Timber.w("Max ANT+ reconnection attempts reached")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("ConnectionService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.d("ConnectionService onDestroy")
        try {
            reconnectJob?.cancel()
            antReconnectJob?.cancel()
            try {
                serviceScope?.cancel()
            } catch (e: Exception) {
                Timber.e(e, "Error canceling service scope")
            }
            currentBluetoothService?.disconnect()
            antManager.disconnect()
        } catch (e: Exception) {
            Timber.e(e, "Error during service destruction")
        }
        super.onDestroy()
    }
}