package com.enderthor.kremote.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.enderthor.kremote.extension.KremoteExtension
import timber.log.Timber


class RideAppReceiver(private val extension: KremoteExtension) : BroadcastReceiver() {


    override fun onReceive(context: Context, intent: Intent) {

        if (intent.action == "io.hammerhead.intent.action.RIDE_APP_OPENED") {
            Timber.d("Ride app opened detected")
            extension.onRideApp = true
            // Aquí puedes agregar el código que deseas ejecutar cuando se detecte el intent
        }
        if (intent.action == "io.hammerhead.hx.intent.action.RIDE_STOP") {
            Timber.d("Ride app closed detected")
            extension.onRideApp = false
            // Aquí puedes agregar el código que deseas ejecutar cuando se detecte el intent
        }
    }
}