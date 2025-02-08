package com.enderthor.kremote.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteType
import com.enderthor.kremote.permissions.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class BluetoothManager {
    constructor(
        context: Context,
        permissionManager: PermissionManager
    ) {
        this.context = context
        this.permissionManager = permissionManager
        initialize()
    }

    constructor(context: Context) {
        this.context = context
        this.permissionManager = null
        initialize()
    }

    private val context: Context
    private val permissionManager: PermissionManager?
    private var bluetoothManager: android.bluetooth.BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val bluetoothLeScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val connectedDevices = ConcurrentHashMap<String, BluetoothService>()

    private lateinit var _scanResults: MutableStateFlow<List<BluetoothDevice>>
    val scanResults: StateFlow<List<BluetoothDevice>> get() = _scanResults

    private lateinit var _isScanning: MutableStateFlow<Boolean>
    val isScanning: StateFlow<Boolean> get() = _isScanning

    private lateinit var _errorState: MutableStateFlow<String?>
    val errorState: StateFlow<String?> get() = _errorState

    private fun initialize() {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        _scanResults = MutableStateFlow(emptyList())
        _isScanning = MutableStateFlow(false)
        _errorState = MutableStateFlow(null)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                if (!checkBluetoothPermission()) {
                    stopScan()
                    _errorState.value = "Bluetooth permissions not granted"
                    return
                }

                val device = result.device
                if (isValidBluetoothDevice(device)) {
                    val currentList = _scanResults.value.toMutableList()
                    if (!currentList.contains(device)) {
                        currentList.add(device)
                        _scanResults.value = currentList
                        Timber.d("Device found: ${getDeviceName(device)} - ${getDeviceAddress(device)}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing scan result")
                _errorState.value = "Error processing scan result: ${e.message}"
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            val errorMessage = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scan not supported"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal scan error"
                else -> "Scan failed with error: $errorCode"
            }
            Timber.e(errorMessage)
            _errorState.value = errorMessage
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            if (!checkBluetoothPermission()) {
                stopScan()
                return
            }

            results.forEach { result ->
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (permissionManager != null) {
            permissionManager.areBluetoothPermissionsGranted()
        } else {
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

    private fun isValidBluetoothDevice(device: BluetoothDevice): Boolean {
        if (!checkBluetoothPermission()) return false

        return try {
            val hasValidName = !device.name.isNullOrBlank()
            val hasValidAddress = !device.address.isNullOrBlank()

            val isLeDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                        device.type == BluetoothDevice.DEVICE_TYPE_DUAL
            } else {
                true
            }

            hasValidName && hasValidAddress && isLeDevice
        } catch (e: SecurityException) {
            Timber.w(e, "Security exception while validating device")
            false
        }
    }

    fun startScan() {
        if (_isScanning.value) {
            Timber.d("Scan already in progress")
            return
        }

        if (!checkBluetoothPermission()) {
            _errorState.value = "Bluetooth permissions not granted"
            return
        }

        try {
            if (!isBluetoothEnabled()) {
                _errorState.value = "Bluetooth is not enabled"
                return
            }

            _scanResults.value = emptyList()
            _isScanning.value = true
            _errorState.value = null

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bluetoothLeScanner?.startScan(null, settings, scanCallback) ?: run {
                _isScanning.value = false
                _errorState.value = "BluetoothLeScanner not available"
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting scan")
            _isScanning.value = false
            _errorState.value = "Error starting scan: ${e.message}"
        }
    }

    fun stopScan() {
        try {
            if (checkBluetoothPermission()) {
                bluetoothLeScanner?.stopScan(scanCallback)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping scan")
        } finally {
            _isScanning.value = false
        }
    }

    fun createBluetoothService(onKeyPress: (Int) -> Unit): BluetoothService? {
        return if (checkBluetoothPermission()) {
            BluetoothService(context, permissionManager, onKeyPress)
        } else {
            Timber.w("Cannot create BluetoothService: permissions not granted")
            null
        }
    }

    suspend fun connectToDevice(device: RemoteDevice): Boolean {
        if (!checkBluetoothPermission()) {
            _errorState.value = "Bluetooth permissions not granted"
            return false
        }

        return try {
            val macAddress = device.macAddress ?: throw IllegalArgumentException("Device MAC address is null")
            val bluetoothDevice = getBluetoothDeviceByAddress(macAddress) ?: throw IllegalStateException("Could not get device with address: $macAddress")

            val service = createBluetoothService { keyCode ->
                Timber.d("Key press received from device ${device.id}: $keyCode")
            } ?: throw IllegalStateException("Could not create BluetoothService")

            connectedDevices[device.id] = service
            service.connect(bluetoothDevice)

            withTimeout(CONNECTION_TIMEOUT) {
                service.connectionState.first { it == BluetoothService.ConnectionState.CONNECTED }
            }
            _errorState.value = null
            true
        } catch (e: Exception) {
            Timber.e(e, "Error connecting to device: ${device.name}")
            _errorState.value = "Error connecting to device: ${e.message}"
            false
        }
    }

    fun disconnectDevice(deviceId: String) {
        try {
            if (!checkBluetoothPermission()) {
                _errorState.value = "Bluetooth permissions not granted"
                return
            }

            connectedDevices[deviceId]?.let { service ->
                service.disconnect()
                connectedDevices.remove(deviceId)
                Timber.d("Device disconnected: $deviceId")
                _errorState.value = null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting device: $deviceId")
            _errorState.value = "Error disconnecting device: ${e.message}"
        }
    }

    fun isDeviceConnected(deviceId: String): Boolean {
        return if (checkBluetoothPermission()) {
            connectedDevices[deviceId]?.connectionState?.value == BluetoothService.ConnectionState.CONNECTED
        } else false
    }

    fun getBluetoothDeviceByAddress(address: String): BluetoothDevice? {
        if (!checkBluetoothPermission()) {
            _errorState.value = "Bluetooth permissions not granted"
            return null
        }

        return try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            Timber.e(e, "Error getting device with address: $address")
            _errorState.value = "Error getting device: ${e.message}"
            null
        }
    }

    fun getDeviceName(device: BluetoothDevice): String {
        return if (checkBluetoothPermission()) {
            try {
                device.name ?: "Unknown Device"
            } catch (e: SecurityException) {
                Timber.w(e, "Security exception while getting device name")
                "Unknown Device"
            }
        } else "Unknown Device"
    }

    fun getDeviceAddress(device: BluetoothDevice): String {
        return if (checkBluetoothPermission()) {
            try {
                device.address
            } catch (e: SecurityException) {
                Timber.w(e, "Security exception while getting device address")
                "Unknown Address"
            }
        } else "Unknown Address"
    }

    fun isBluetoothEnabled(): Boolean {
        return if (checkBluetoothPermission()) {
            bluetoothAdapter?.isEnabled == true
        } else false
    }

    fun cleanup() {
        stopScan()
        if (checkBluetoothPermission()) {
            connectedDevices.forEach { (_, service) ->
                service.disconnect()
            }
        }
        connectedDevices.clear()
        _errorState.value = null
    }

    companion object {
        private const val CONNECTION_TIMEOUT = 10000L // 10 segundos
    }
}