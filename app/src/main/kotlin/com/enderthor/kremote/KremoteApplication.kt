package com.enderthor.kremote

import android.app.Application
import android.content.Intent
import android.util.Log
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.DEBUG_LOGGING_ENABLED
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.receiver.ConnectionServiceReceiver
import com.enderthor.kremote.service.ConnectionService
import timber.log.Timber
import timber.log.Timber.DebugTree
import timber.log.Timber.Forest.plant
import timber.log.Timber.Tree

class KremoteApplication : Application() {
    private lateinit var repository: RemoteRepository

    override fun onCreate() {
        super.onCreate()

        val forceDebug = true

        if (BuildConfig.DEBUG || forceDebug) {
            plant(object : DebugTree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    Log.println(
                        priority,
                        tag,
                        message + (if (t == null) "" else "\n" + t.message + "\n" + Log.getStackTraceString(
                            t
                        ))
                    )
                }
            })
        } else {
            Timber.plant(object : Tree() {
                override fun isLoggable(tag: String?, priority: Int): Boolean {
                    return priority > Log.WARN
                }

                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    Log.println(
                        priority,
                        tag,
                        message + (if (t == null) "" else "\n" + t.message + "\n" + Log.getStackTraceString(
                            t
                        ))
                    )
                }
            })
        }
        Timber.d("KREMOTE APP START")

        // Inicializar DebugLogger en la aplicación principal
        DebugLogger.initialize(applicationContext, DEBUG_LOGGING_ENABLED)

        repository = RemoteRepository(applicationContext)

        startConnectionService()

    }

    private fun startConnectionService() {
        try {
            DebugLogger.logConnectionEvent(0, "APP_START_SERVICE", "Attempting to start ConnectionService directly", "KremoteApplication")
            Timber.d("[KremoteApplication] Iniciando ConnectionService directamente")

            // NUEVO: Iniciar el servicio directamente en lugar de usar broadcast
            val serviceIntent = Intent(this, ConnectionService::class.java)

            try {
                startForegroundService(serviceIntent)
                DebugLogger.logConnectionEvent(0, "SERVICE_STARTED_DIRECT", "Foreground service started successfully", "KremoteApplication")
                Timber.d("[KremoteApplication] ConnectionService iniciado directamente")
            } catch (e: Exception) {
                // Fallback al método anterior (broadcast) si el directo falla
                DebugLogger.logConnectionEvent(0, "FALLBACK_TO_BROADCAST", "Direct start failed, using broadcast", "KremoteApplication")
                val intent = Intent("com.enderthor.kremote.START_CONNECTION_SERVICE")
                intent.putExtra(ConnectionServiceReceiver.EXTRA_IS_EXTENSION, false)
                sendBroadcast(intent, "com.enderthor.kremote.PERMISSION_START_CONNECTION")
                DebugLogger.logConnectionEvent(0, "BROADCAST_SENT", "Broadcast sent as fallback", "KremoteApplication")
            }
        } catch (e: Exception) {
            DebugLogger.logError("APP", "Error starting ConnectionService", e, "KremoteApplication")
            Timber.e(e, "Error starting ConnectionService")
        }
    }
}