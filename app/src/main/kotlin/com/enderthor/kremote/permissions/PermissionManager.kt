package com.enderthor.kremote.permissions

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import timber.log.Timber

class PermissionManager(private val activity: ComponentActivity) {
    private val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            onPermissionsGranted?.invoke()
        } else {
            onPermissionsDenied?.invoke()
            Timber.w("Some permissions were denied: ${permissions.filter { !it.value }.keys}")
        }
    }

    private val bluetoothEnableLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onBluetoothEnabled?.invoke()
        } else {
            onBluetoothDenied?.invoke()
            Timber.w("Bluetooth enable request was denied")
        }
    }

    var onPermissionsGranted: (() -> Unit)? = null
    var onPermissionsDenied: (() -> Unit)? = null
    var onBluetoothEnabled: (() -> Unit)? = null
    var onBluetoothDenied: (() -> Unit)? = null

    fun areBluetoothPermissionsGranted(): Boolean {
        return bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestBluetoothPermissions(
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        onPermissionsGranted = onGranted
        onPermissionsDenied = onDenied

        if (areBluetoothPermissionsGranted()) {
            onGranted()
        } else {
            permissionLauncher.launch(bluetoothPermissions)
        }
    }

    fun checkBluetoothEnabled(
        onEnabled: () -> Unit,
        onDisabled: () -> Unit
    ) {
        onBluetoothEnabled = onEnabled
        onBluetoothDenied = onDisabled

        bluetoothAdapter?.let { adapter ->
            if (!adapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                bluetoothEnableLauncher.launch(enableBtIntent)
            } else {
                onEnabled()
            }
        } ?: run {
            Timber.e("Bluetooth adapter not available")
            onDisabled()
        }
    }

    fun cleanup() {
        onPermissionsGranted = null
        onPermissionsDenied = null
        onBluetoothEnabled = null
        onBluetoothDenied = null
    }
}