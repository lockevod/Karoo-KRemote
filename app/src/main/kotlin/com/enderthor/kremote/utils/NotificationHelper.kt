package com.enderthor.kremote.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ID = "kremote_service_channel"
    const val CHANNEL_ID_WARNING = "kremote_warning_channel"
    const val NOTIFICATION_ID = 1001
    private const val CHANNEL_NAME = "KRemote Service"
    private const val CHANNEL_NAME_WARNING = "KRemote Warnings"
    private const val CHANNEL_DESCRIPTION = "Notificaciones del servicio de conexión KRemote"
    private const val CHANNEL_DESCRIPTION_WARNING = "Avisos y problemas de KRemote"

    fun createNotificationChannels(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Canal normal
        val normalChannel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
        }

        // Canal de advertencias
        val warningChannel = NotificationChannel(
            CHANNEL_ID_WARNING,
            CHANNEL_NAME_WARNING,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION_WARNING
            setShowBadge(true)
            enableVibration(true)
        }

        notificationManager.createNotificationChannels(listOf(normalChannel, warningChannel))
    }

    fun createServiceNotification(
        context: Context,
        title: String = "KRemote - Servicio activo",
        content: String = "Servicio de conexión ANT+ en ejecución",
        isWarning: Boolean = false
    ): Notification {
        // Asegurar que los canales existen
        createNotificationChannels(context)

        // Intent para abrir la app cuando se toque la notificación
        val intent = Intent().apply {
            setClassName(context.packageName, "${context.packageName}.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isWarning) CHANNEL_ID_WARNING else CHANNEL_ID
        val icon = if (isWarning) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_menu_mylocation
        val priority = if (isWarning) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Si es advertencia, añadir más información
        if (isWarning) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$content\n\nPara solucionarlo:\n1. Reinicia tu dispositivo Karoo\n2. Vuelve a abrir KRemote")
            )
        } else {
            builder.setSilent(true)
        }

        return builder.build()
    }
}
