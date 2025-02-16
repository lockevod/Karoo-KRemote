package com.enderthor.kremote.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.enderthor.kremote.service.ConnectionService
import timber.log.Timber


class ConnectionServiceReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_IS_EXTENSION = "is_extension"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, ConnectionService::class.java)
        val isExtension = intent.getBooleanExtra(EXTRA_IS_EXTENSION, false)

        try {
            if (isExtension) {
                context.startService(serviceIntent)
            } else {
                context.startForegroundService(serviceIntent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting service")
        }
    }
}