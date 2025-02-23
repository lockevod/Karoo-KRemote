package com.enderthor.kremote

import android.app.Application
import android.content.Intent
import android.util.Log
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.receiver.ConnectionServiceReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import timber.log.Timber.DebugTree
import timber.log.Timber.Forest.plant
import timber.log.Timber.Tree

class KremoteApplication : Application() {
    private lateinit var repository: RemoteRepository
    private val applicationScope = CoroutineScope(Dispatchers.IO)

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
                    return priority > Log.DEBUG
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


        repository = RemoteRepository(applicationContext)

        applicationScope.launch {
            try {


                    startConnectionService()

            } catch (e: Exception) {
                Timber.e(e, "Error initializing application")
            }
        }
    }

    private fun startConnectionService() {
        try {
            val intent = Intent("com.enderthor.kremote.START_CONNECTION_SERVICE")
            intent.putExtra(ConnectionServiceReceiver.EXTRA_IS_EXTENSION, false)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Timber.e(e, "Error starting ConnectionService")
        }
    }
}