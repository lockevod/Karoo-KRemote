package com.enderthor.kremote

import android.app.Application
import android.content.Intent
import android.util.Log
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.DEBUG_LOGGING_ENABLED
import com.enderthor.kremote.utils.DebugLogger
import com.enderthor.kremote.receiver.ConnectionServiceReceiver
import timber.log.Timber
import timber.log.Timber.DebugTree
import timber.log.Timber.Forest.plant
import timber.log.Timber.Tree

class KremoteApplication : Application() {
    private lateinit var repository: RemoteRepository

    override fun onCreate() {
        super.onCreate()

        val forceDebug = false

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
            DebugLogger.logConnectionEvent(0, "APP_START_SERVICE", "Attempting to start ConnectionService via broadcast", "KremoteApplication")
            Timber.d("[KremoteApplication] Enviando broadcast para iniciar ConnectionService")

            val intent = Intent("com.enderthor.kremote.START_CONNECTION_SERVICE")
            intent.putExtra(ConnectionServiceReceiver.EXTRA_IS_EXTENSION, false)
            // ARREGLADO: Usar el permiso requerido por el receiver
            sendBroadcast(intent, "com.enderthor.kremote.PERMISSION_START_CONNECTION")

            DebugLogger.logConnectionEvent(0, "BROADCAST_SENT", "Broadcast sent successfully with permission", "KremoteApplication")
            Timber.d("[KremoteApplication] Broadcast enviado correctamente con permiso")
        } catch (e: Exception) {
            DebugLogger.logError("APP", "Error starting ConnectionService", e, "KremoteApplication")
            Timber.e(e, "Error starting ConnectionService")
        }
    }
}