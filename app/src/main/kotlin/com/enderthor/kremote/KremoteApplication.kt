package com.enderthor.kremote

import android.app.Application
import android.content.Intent
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.service.ConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class KremoteApplication : Application() {
    private lateinit var repository: RemoteRepository
    private val applicationScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        repository = RemoteRepository(applicationContext)

        applicationScope.launch {
            try {
                val config = repository.currentConfig.first()
                if (config.globalSettings.autoConnectOnStart) {
                    startConnectionService()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error initializing application")
            }
        }
    }

    private fun startConnectionService() {
        try {
            val serviceIntent = Intent(this, ConnectionService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {
            Timber.e(e, "Error starting ConnectionService")
        }
    }
}