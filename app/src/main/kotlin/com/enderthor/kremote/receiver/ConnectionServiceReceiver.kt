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

        // NUEVO: Pasar el parámetro is_extension al servicio
        serviceIntent.putExtra("is_extension", isExtension)

        try {
            // CAMBIO: Siempre usar startForegroundService para evitar problemas
            // El servicio se encargará internamente de manejar la diferencia
            DebugLogger.logConnectionEvent(0, "STARTING_FOREGROUND_SERVICE", "Starting as foreground service (mode: ${if (isExtension) "extension" else "app"})", "ConnectionServiceReceiver")
            context.startForegroundService(serviceIntent)

            Timber.d("[ConnectionServiceReceiver] Servicio iniciado correctamente en modo ${if (isExtension) "extensión" else "app"}")
        } catch (e: Exception) {
            DebugLogger.logError("RECEIVER", "Error starting service", e, "ConnectionServiceReceiver")
            Timber.e(e, "Error starting service")
        }
    }
}