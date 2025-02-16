package com.enderthor.kremote.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.enderthor.kremote.screens.TabLayout
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.ant.AntManager
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import io.hammerhead.karooext.KarooSystemService

import timber.log.Timber

class MainActivity : ComponentActivity() {
    private lateinit var repository: RemoteRepository
    private lateinit var antManager: AntManager
    private lateinit var karooSystem: KarooSystemService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupDependencies()
        setContent {
            MainScreen(
                repository = repository,
                antManager = antManager,
                karooSystem = karooSystem
            )
        }
    }

    private fun setupDependencies() {
        repository = RemoteRepository(applicationContext)
        antManager = AntManager(this) { command ->
            handleAntCommand(command)
        }
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (connected) {
                Timber.i("Karoo system service connected: $connected")
            }
        }
    }

    private fun handleAntCommand(command: GenericCommandNumber) {
        Timber.d("ANT+ command received: $command")
        // Aquí puedes manejar los comandos ANT+ según sea necesario
    }



    override fun onDestroy() {
        super.onDestroy()
        antManager.cleanup()
    }
}

@Composable
fun MainScreen(
    repository: RemoteRepository,
    antManager: AntManager,
    karooSystem: KarooSystemService
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        TabLayout(
            repository = repository,
            antManager = antManager,
            karooSystem = karooSystem
        )
    }
}