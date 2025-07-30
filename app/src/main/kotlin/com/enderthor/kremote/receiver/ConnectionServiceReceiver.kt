package com.enderthor.kremote.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.enderthor.kremote.service.ConnectionService
import com.enderthor.kremote.utils.DebugLogger
import timber.log.Timber


class ConnectionServiceReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_IS_EXTENSION = "is_extension"
    }

    override fun onReceive(context: Context, intent: Intent) {
        DebugLogger.logConnectionEvent(0, "RECEIVER_BROADCAST", "ConnectionServiceReceiver received broadcast", "ConnectionServiceReceiver")
        Timber.d("[ConnectionServiceReceiver] Broadcast recibido")

        val serviceIntent = Intent(context, ConnectionService::class.java)
        val isExtension = intent.getBooleanExtra(EXTRA_IS_EXTENSION, false)

        try {
            if (isExtension) {
                // Desde la extensión (contexto del sistema), podemos iniciar servicios normalmente
                DebugLogger.logConnectionEvent(0, "STARTING_SERVICE", "Starting as regular service (extension mode)", "ConnectionServiceReceiver")
                context.startService(serviceIntent)
            } else {
                // Desde la app, usar JobScheduler o WorkManager para evitar restricciones de background
                // Por ahora, intentar iniciar como foreground solo si no hay restricciones
                try {
                    DebugLogger.logConnectionEvent(0, "STARTING_FOREGROUND_SERVICE", "Starting as foreground service (app mode)", "ConnectionServiceReceiver")
                    context.startForegroundService(serviceIntent)
                } catch (e: IllegalStateException) {
                    // Si falla por restricciones de background, iniciar como servicio regular
                    DebugLogger.logConnectionEvent(0, "FALLBACK_SERVICE", "Fallback to regular service due to background restrictions", "ConnectionServiceReceiver")
                    context.startService(serviceIntent)
                }
            }
            Timber.d("[ConnectionServiceReceiver] Servicio iniciado correctamente")
        } catch (e: Exception) {
            DebugLogger.logError("RECEIVER", "Error starting service", e, "ConnectionServiceReceiver")
            Timber.e(e, "Error starting service")
        }
    }
}