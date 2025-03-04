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
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.EXTENSION_NAME
import io.hammerhead.karooext.KarooSystemService
import com.enderthor.kremote.data.PressType
import io.hammerhead.karooext.models.RequestAnt

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
            )
        }
    }

    private fun setupDependencies() {
        repository = RemoteRepository(applicationContext)
        antManager = AntManager(
            context = this,
            commandCallback = { command: AntRemoteKey, pressType: PressType ->
                handleAntCommand(command, pressType)
            }
        )
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (connected) {
                Timber.i("Karoo system service connected")
                karooSystem.dispatch(RequestAnt(EXTENSION_NAME+"_A"))
            }
        }
    }

    private fun handleAntCommand(command: AntRemoteKey, pressType: PressType) {
        Timber.d("ANT+ command received: $command and pressType: $pressType")
        // Aquí puedes manejar los comandos ANT+ según sea necesario
    }



    override fun onDestroy() {
        antManager.disconnect()
        antManager.cleanup()
        //karooSystem.dispatch(ReleaseAnt(EXTENSION_NAME))
        karooSystem.disconnect()
        super.onDestroy()
    }
}

@Composable
fun MainScreen(
    repository: RemoteRepository,
    antManager: AntManager,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        TabLayout(
            repository = repository,
            antManager = antManager,
        )
    }
}