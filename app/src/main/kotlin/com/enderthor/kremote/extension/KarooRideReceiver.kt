package com.enderthor.kremote.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import timber.log.Timber

class KarooRideReceiver(private val onRideStateChanged: (Boolean) -> Unit) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_RIDE_START -> {
                Timber.d("Ride started")
                onRideStateChanged(true)
            }
            ACTION_RIDE_STOP -> {
                Timber.d("Ride stopped")
                onRideStateChanged(false)
            }
        }
    }

    companion object {
        private const val ACTION_RIDE_START = "io.hammerhead.action.RIDE_START"
        private const val ACTION_RIDE_STOP = "io.hammerhead.action.RIDE_STOP"

        fun getIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(ACTION_RIDE_START)
                addAction(ACTION_RIDE_STOP)
            }
        }
    }
}