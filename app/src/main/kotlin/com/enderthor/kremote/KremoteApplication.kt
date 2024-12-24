package com.enderthor.kremote

import android.app.Application
import timber.log.Timber


class KremoteApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        //Timber.plant(Timber.DebugTree())

        Timber.d("Starting KRemote App")
    }
}
