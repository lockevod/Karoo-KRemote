package com.enderthor.kremote.extension

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import com.enderthor.kremote.BuildConfig
import com.enderthor.kremote.manager.AntRemoteManager
import com.enderthor.kremote.manager.KarooActionManager
import com.enderthor.kremote.receiver.RideAppReceiver
import io.hammerhead.karooext.models.ReleaseAnt
import io.hammerhead.karooext.models.RequestAnt
import timber.log.Timber

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class KremoteExtension : KarooExtension("kremote", BuildConfig.VERSION_NAME) {

    lateinit var karooSystem: KarooSystemService
    private lateinit var rideAppReceiver: RideAppReceiver
    private lateinit var antRemoteManager: AntRemoteManager
    private lateinit var karooActionManager: KarooActionManager
    var onRideApp: Boolean = false


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            Timber.i("Karoo system service connected: $connected")
        }
       // Timber.d("oncreate")

        karooSystem.dispatch(RequestAnt(extension))
        karooActionManager = KarooActionManager(karooSystem)
        antRemoteManager = AntRemoteManager(this, applicationContext, karooActionManager)
        antRemoteManager.startAntRemote()

        rideAppReceiver = RideAppReceiver(this)

        val filter = IntentFilter().apply {
            addAction("io.hammerhead.intent.action.RIDE_APP_OPENED")
            addAction("io.hammerhead.hx.intent.action.RIDE_STOP")
        }
        registerReceiver(rideAppReceiver, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(rideAppReceiver)
        antRemoteManager.closeAntHandler()
        karooSystem.dispatch(ReleaseAnt(extension))
        karooSystem.disconnect()
        super.onDestroy()
    }
}