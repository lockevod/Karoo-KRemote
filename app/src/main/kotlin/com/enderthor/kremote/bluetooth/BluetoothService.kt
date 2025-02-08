package com.enderthor.kremote.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.enderthor.kremote.permissions.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

class BluetoothService(
    private val context: Context,
    private val permissionManager: PermissionManager?,
    private val onKeyPress: (Int) -> Unit
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState

    private val characteristicQueue = ConcurrentLinkedQueue<BluetoothGattCharacteristic>()
    private var isProcessingQueue = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (!checkBluetoothPermission()) {
                            Timber.e("Missing permissions for connected device")
                            _errorState.value = "Missing Bluetooth permissions"
                            _connectionState.value = ConnectionState.ERROR
                            return
                        }

                        Timber.i("Successfully connected to $deviceAddress")
                        _connectionState.value = ConnectionState.CONNECTED
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            Timber.e(e, "Security exception during service discovery")
                            _errorState.value = "Security error during service discovery"
                            _connectionState.value = ConnectionState.ERROR
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.i("Disconnected from $deviceAddress")
                        _connectionState.value = ConnectionState.DISCONNECTED
                        cleanupConnection()
                    }
                }
            } else {
                val errorMsg = "Error $status encountered for $deviceAddress"
                Timber.w(errorMsg)
                _errorState.value = errorMsg
                _connectionState.value = ConnectionState.ERROR
                cleanupConnection()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (!checkBluetoothPermission()) {
                    Timber.e("Missing permissions for service discovery")
                    _errorState.value = "Missing Bluetooth permissions"
                    return
                }
                processGattServices(gatt)
            } else {
                val errorMsg = "Service discovery failed with status: $status"
                Timber.w(errorMsg)
                _errorState.value = errorMsg
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processButtonPress(value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                isProcessingQueue = false
                processNextCharacteristic()
            } else {
                val errorMsg = "Descriptor write failed with status: $status"
                Timber.e(errorMsg)
                _errorState.value = errorMsg
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (permissionManager != null) {
            permissionManager.areBluetoothPermissionsGranted()
        } else {
            // Comprobación básica de permisos cuando no tenemos PermissionManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checkPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                        checkPermission(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                checkPermission(Manifest.permission.BLUETOOTH) &&
                        checkPermission(Manifest.permission.BLUETOOTH_ADMIN) &&
                        checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun checkPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun processGattServices(gatt: BluetoothGatt) {
        if (!checkBluetoothPermission()) {
            Timber.e("Missing permissions for processing GATT services")
            _errorState.value = "Missing Bluetooth permissions"
            return
        }

        try {
            gatt.services?.forEach { service ->
                Timber.d("Found service: ${service.uuid}")
                service.characteristics.forEach { characteristic ->
                    if (isNotifyCharacteristic(characteristic)) {
                        Timber.d("Found notify characteristic: ${characteristic.uuid}")
                        characteristicQueue.offer(characteristic)
                    }
                }
            }

            if (!isProcessingQueue) {
                processNextCharacteristic()
            }
        } catch (e: Exception) {
            val errorMsg = "Error processing GATT services: ${e.message}"
            Timber.e(e, errorMsg)
            _errorState.value = errorMsg
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private fun isNotifyCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        return (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    private fun processNextCharacteristic() {
        if (isProcessingQueue) return

        val characteristic = characteristicQueue.poll() ?: run {
            isProcessingQueue = false
            return
        }

        if (!checkBluetoothPermission()) {
            Timber.e("Missing permissions for processing characteristic")
            _errorState.value = "Missing Bluetooth permissions"
            return
        }

        isProcessingQueue = true

        try {
            bluetoothGatt?.let { gatt ->
                gatt.setCharacteristicNotification(characteristic, true)

                characteristic.descriptors.forEach { descriptor ->
                    val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    descriptor.value = value
                    gatt.writeDescriptor(descriptor)
                }
            } ?: run {
                isProcessingQueue = false
                val errorMsg = "BluetoothGatt is null"
                Timber.e(errorMsg)
                _errorState.value = errorMsg
            }
        } catch (e: SecurityException) {
            isProcessingQueue = false
            val errorMsg = "Security exception processing characteristic: ${e.message}"
            Timber.e(e, errorMsg)
            _errorState.value = errorMsg
            _connectionState.value = ConnectionState.ERROR
        } catch (e: Exception) {
            isProcessingQueue = false
            val errorMsg = "Error processing characteristic: ${e.message}"
            Timber.e(e, errorMsg)
            _errorState.value = errorMsg
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private fun processButtonPress(value: ByteArray) {
        try {
            if (value.isNotEmpty()) {
                val hexString = value.joinToString(", ") { String.format("0x%02X", it) }
                Timber.d("Received value: $hexString")

                val keyCode = interpretKeyCode(value)
                keyCode?.let {
                    Timber.d("Interpreted key code: $it")
                    onKeyPress(it)
                }
            }
        } catch (e: Exception) {
            val errorMsg = "Error processing button press: ${e.message}"
            Timber.e(e, errorMsg)
            _errorState.value = errorMsg
        }
    }

    private fun interpretKeyCode(value: ByteArray): Int? {
        return try {
            when {
                value.size == 1 -> value[0].toInt()
                value.size >= 2 -> {
                    val combinedValue = (value[0].toInt() and 0xFF) or
                            ((value[1].toInt() and 0xFF) shl 8)
                    combinedValue
                }
                else -> null
            }
        } catch (e: Exception) {
            val errorMsg = "Error interpreting key code: ${e.message}"
            Timber.e(e, errorMsg)
            _errorState.value = errorMsg
            null
        }
    }

    fun connect(device: BluetoothDevice) {
        if (!checkBluetoothPermission()) {
            val errorMsg = "Missing Bluetooth permissions for connection"
            Timber.e(errorMsg)
            _errorState.value = errorMsg
            _connectionState.value = ConnectionState.ERROR
            return
        }

        try {
            _connectionState.value = ConnectionState.CONNECTING
            _errorState.value = null
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: SecurityException) {
            val errorMsg = "Security exception connecting to device: ${e.message}"
            Timber.e(e, errorMsg)
            _errorState.value = errorMsg
            _connectionState.value = ConnectionState.ERROR
        } catch (e: Exception) {
            val errorMsg = "Error connecting to device: ${e.message}"
            Timber.e(e, errorMsg)
            _errorState.value = errorMsg
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun disconnect() {
        if (!checkBluetoothPermission()) {
            Timber.e("Missing permissions for disconnection")
            return
        }

        try {
            bluetoothGatt?.disconnect()
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception while disconnecting")
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting")
        } finally {
            cleanupConnection()
        }
    }

    private fun cleanupConnection() {
        try {
            if (checkBluetoothPermission()) {
                bluetoothGatt?.close()
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception during cleanup")
        } catch (e: Exception) {
            Timber.e(e, "Error during cleanup")
        } finally {
            bluetoothGatt = null
            isProcessingQueue = false
            characteristicQueue.clear()
            _errorState.value = null
        }
    }

    enum class ConnectionState {
        CONNECTED,
        DISCONNECTED,
        CONNECTING,
        ERROR
    }
}