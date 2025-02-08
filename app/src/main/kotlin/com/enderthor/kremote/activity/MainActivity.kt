package com.enderthor.kremote.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.enderthor.kremote.permissions.PermissionManager
import com.enderthor.kremote.screens.TabLayout
import com.enderthor.kremote.bluetooth.BluetoothManager
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.ant.AntManager
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var repository: RemoteRepository
    private lateinit var antManager: AntManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupDependencies()
        setContent {
            MainScreen(
                bluetoothManager = bluetoothManager,
                repository = repository,
                permissionManager = permissionManager,
                antManager = antManager
            )
        }
    }

    private fun setupDependencies() {
        setupPermissionManager()
        repository = RemoteRepository(applicationContext)
        bluetoothManager = BluetoothManager(this, permissionManager)
        antManager = AntManager(this) { command ->
            handleAntCommand(command)
        }
    }

    private fun handleAntCommand(command: GenericCommandNumber) {
        Timber.d("ANT+ command received: $command")
        // Aquí puedes manejar los comandos ANT+ según sea necesario
    }

    private fun setupPermissionManager() {
        permissionManager = PermissionManager(this)

        permissionManager.onBluetoothEnabled = {
            Timber.d("Bluetooth enabled")
        }

        permissionManager.onBluetoothDenied = {
            Toast.makeText(
                this,
                "Se requiere Bluetooth para usar la aplicación",
                Toast.LENGTH_LONG
            ).show()
        }

        permissionManager.onPermissionsGranted = {
            Timber.d("Bluetooth permissions granted")
        }

        permissionManager.onPermissionsDenied = {
            Toast.makeText(
                this,
                "Se requieren permisos para usar la aplicación",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionManager.cleanup()
        bluetoothManager.cleanup()
        antManager.cleanup()
    }
}

@Composable
fun MainScreen(
    bluetoothManager: BluetoothManager,
    repository: RemoteRepository,
    permissionManager: PermissionManager,
    antManager: AntManager
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        TabLayout(
            bluetoothManager = bluetoothManager,
            repository = repository,
            permissionManager = permissionManager,
            antManager = antManager
        )
    }
}