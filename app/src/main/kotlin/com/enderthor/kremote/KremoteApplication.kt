package com.enderthor.kremote

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Environment
import androidx.annotation.RequiresApi
import com.enderthor.kremote.utils.FileLoggingTree
import timber.log.Timber
import java.io.File


class KremoteApplication : Application() {

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate() {
        super.onCreate()
        //Timber.plant(Timber.DebugTree())
        if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", this.packageName, null)
                intent.data = uri
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }

            val dir = File("//sdcard//")
            val logfile = File(dir, "powerlog.txt")
            Timber.plant(FileLoggingTree(logfile))

        Timber.d("Starting KRemote App")
    }
}
