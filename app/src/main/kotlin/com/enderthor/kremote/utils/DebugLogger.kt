package com.enderthor.kremote.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.lang.ref.WeakReference

object DebugLogger {
    private var isDebugEnabled = false
    private var logFile: File? = null
    private var contextRef: WeakReference<Context>? = null

    fun initialize(context: Context, enabled: Boolean = false) {
        // Usar ApplicationContext para evitar memory leaks
        this.contextRef = WeakReference(context.applicationContext)
        isDebugEnabled = enabled
        createLogFileIfNeeded()
    }

    fun setEnabled(enabled: Boolean) {
        isDebugEnabled = enabled
        if (enabled) {
            createLogFileIfNeeded()
            logConnectionEvent(0, "DEBUG_MODE_ENABLED", "Debug logging activated from menu")
        }
    }

    private fun createLogFileIfNeeded() {
        val context = contextRef?.get()
        if (isDebugEnabled && context != null) {
            try {
                // Usar getExternalFilesDir para que sea más accesible
                val debugDir = context.getExternalFilesDir("debug") ?: context.filesDir
                debugDir.mkdirs() // Asegurar que el directorio existe

                logFile = File(debugDir, "kremote_debug.log")

                Timber.d("Debug directory: ${debugDir.absolutePath}")
                Timber.d("Debug file path: ${logFile!!.absolutePath}")
                Timber.d("Directory exists: ${debugDir.exists()}")
                Timber.d("Directory writable: ${debugDir.canWrite()}")

                // Limpiar log anterior si es muy grande (>5MB)
                logFile?.let { file ->
                    if (file.exists() && file.length() > 5 * 1024 * 1024) {
                        file.delete()
                        Timber.d("Deleted large log file")
                    }

                    // Log inicial para verificar que funciona
                    val initialMessage = "\n=== KREMOTE DEBUG SESSION STARTED ===\n" +
                            "Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                            "File: ${file.absolutePath}\n"
                    file.appendText(initialMessage)

                    Timber.d("Debug log file created successfully at: ${file.absolutePath}")
                    Timber.d("File size after initial write: ${file.length()} bytes")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error creating debug log file")
                // Fallback a directorio interno si hay problemas
                try {
                    context.let {
                        logFile = File(it.filesDir, "kremote_debug.log")
                        Timber.d("Fallback to internal storage: ${logFile!!.absolutePath}")
                    }
                } catch (e2: Exception) {
                    Timber.e(e2, "Failed to create fallback log file")
                }
            }
        } else if (context == null) {
            Timber.w("Context is null, cannot create debug log file")
        }
    }

    fun isEnabled(): Boolean = isDebugEnabled

    fun logConnectionEvent(deviceNumber: Int, event: String, details: String? = null, source: String? = null) {
        if (!isDebugEnabled) return

        val sourceInfo = source ?: getCallerInfo()
        val message = buildString {
            append("[CONNECTION][$sourceInfo] Device #$deviceNumber: $event")
            details?.let { append(" - $it") }
        }
        writeLog(message)
    }

    fun logKeyEvent(deviceNumber: Int, command: String, pressType: String, processed: Boolean, source: String? = null) {
        if (!isDebugEnabled) return

        val sourceInfo = source ?: getCallerInfo()
        val message = "[$sourceInfo][KEY] Device #$deviceNumber: $command ($pressType) - ${if (processed) "PROCESSED" else "IGNORED"}"
        writeLog(message)
    }

    fun logReconnectionEvent(deviceNumber: Int, attempt: Int, maxAttempts: Int, delay: Long, success: Boolean, source: String? = null) {
        if (!isDebugEnabled) return

        val sourceInfo = source ?: getCallerInfo()
        val message = "[$sourceInfo][RECONNECT] Device #$deviceNumber: Attempt $attempt/$maxAttempts (delay: ${delay}ms) - ${if (success) "SUCCESS" else "FAILED"}"
        writeLog(message)
    }

    fun logDeviceDetection(deviceNumber: Int, deviceName: String, source: String? = null) {
        if (!isDebugEnabled) return

        val sourceInfo = source ?: getCallerInfo()
        val message = "[$sourceInfo][DETECTION] Found device: $deviceName (#$deviceNumber)"
        writeLog(message)
    }

    fun logError(category: String, error: String, exception: Throwable? = null, source: String? = null) {
        if (!isDebugEnabled) return

        val sourceInfo = source ?: getCallerInfo()
        val message = buildString {
            append("[$sourceInfo][ERROR] $category: $error")
            exception?.let { append(" - Exception: ${it.message}") }
        }
        writeLog(message)
        if (exception != null) {
            Timber.e(exception)
        } else {
            Timber.e(message)
        }
    }

    private fun getCallerInfo(): String {
        return try {
            val stackTrace = Thread.currentThread().stackTrace
            // Buscar el primer frame que no sea DebugLogger
            for (i in 3 until stackTrace.size) {
                val element = stackTrace[i]
                if (!element.className.contains("DebugLogger")) {
                    val className = element.className.substringAfterLast('.')
                    val methodName = element.methodName
                    return "$className.$methodName"
                }
            }
            "Unknown"
        } catch (e: Exception) {
            "Unknown " + e.message.orEmpty()
        }
    }

    private fun writeLog(message: String) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $message\n"

        // Log to Timber también
        Timber.d(message)

        // Escribir a archivo si está habilitado
        logFile?.let { file ->
            try {
                file.appendText(logEntry)
            } catch (e: Exception) {
                Timber.e(e, "Error writing to debug log file")
            }
        }
    }

    suspend fun getLogContent(): String = withContext(Dispatchers.IO) {
        logFile?.takeIf { it.exists() }?.readText() ?: "No debug log available"
    }

    fun clearLog() {
        try {
            logFile?.let { file ->
                if (file.exists()) {
                    // Vaciar el contenido del archivo en lugar de borrarlo
                    file.writeText("")

                    // Escribir un mensaje de confirmación
                    val clearMessage = "\n=== LOG LIMPIADO ===\n" +
                            "Tiempo: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                            "Archivo: ${file.absolutePath}\n\n"
                    file.appendText(clearMessage)

                    Timber.d("[DebugLogger] ✅ Log limpiado correctamente")
                } else {
                    Timber.w("[DebugLogger] ⚠️ Archivo de log no existe, no se puede limpiar")
                }
            } ?: run {
                Timber.w("[DebugLogger] ⚠️ LogFile es null, no se puede limpiar")
            }
        } catch (e: Exception) {
            Timber.e(e, "[DebugLogger] ❌ Error limpiando el log")
        }
    }

    fun getLogFile(): File? = logFile
}