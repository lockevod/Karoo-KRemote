package com.enderthor.kremote.utils

import android.content.Context
import androidx.core.content.edit
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
    
    // Configuración de timeout automático para debug (24 horas)
    private const val DEBUG_AUTO_DISABLE_TIMEOUT = 24 * 60 * 60 * 1000L // 24 horas en ms
    private const val PREFS_NAME = "kremote_debug_prefs"
    private const val PREF_DEBUG_ENABLED = "debug_enabled"
    private const val PREF_DEBUG_ENABLED_TIME = "debug_enabled_time"

    fun initialize(context: Context, enabled: Boolean = false) {
        // Usar ApplicationContext para evitar memory leaks
        this.contextRef = WeakReference(context.applicationContext)
        
        // Verificar si hay preferencia guardada y si no ha expirado
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedEnabled = prefs.getBoolean(PREF_DEBUG_ENABLED, false)
        val enabledTime = prefs.getLong(PREF_DEBUG_ENABLED_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        
        // Si el debug estaba habilitado pero ha pasado el timeout, deshabilitarlo
        isDebugEnabled = if (savedEnabled && (currentTime - enabledTime) < DEBUG_AUTO_DISABLE_TIMEOUT) {
            Timber.w("Debug logging restored from previous session (${(currentTime - enabledTime) / (60 * 60 * 1000)}h ago)")
            true
        } else {
            if (savedEnabled) {
                // Limpiar preferencia expirada usando KTX
                prefs.edit { clear() }
                Timber.i("Debug logging auto-disabled due to timeout")
            }
            enabled // Usar valor por defecto
        }
        
        createLogFileIfNeeded()
    }

    fun setEnabled(enabled: Boolean) {
        val context = contextRef?.get()
        isDebugEnabled = enabled
        
        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (enabled) {
                // Guardar preferencia con timestamp usando KTX
                prefs.edit {
                    putBoolean(PREF_DEBUG_ENABLED, true)
                    putLong(PREF_DEBUG_ENABLED_TIME, System.currentTimeMillis())
                }

                createLogFileIfNeeded()
                logConnectionEvent(0, "DEBUG_MODE_ENABLED", "Debug logging activated from menu (will auto-disable in 24h)")
                Timber.w("⚠️ DEBUG LOGGING ENABLED - This may impact performance! Auto-disable in 24h")
            } else {
                // Limpiar preferencia usando KTX
                prefs.edit { clear() }
                logConnectionEvent(0, "DEBUG_MODE_DISABLED", "Debug logging deactivated")
                Timber.i("Debug logging disabled")
            }
        }
    }
    
    /**
     * Obtiene información sobre el estado de debug y cuándo expira
     */
    fun getDebugInfo(): String {
        if (!isDebugEnabled) return "Debug: DISABLED"
        
        val context = contextRef?.get() ?: return "Debug: ENABLED (no context)"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabledTime = prefs.getLong(PREF_DEBUG_ENABLED_TIME, 0L)
        
        if (enabledTime == 0L) return "Debug: ENABLED (session only)"
        
        val currentTime = System.currentTimeMillis()
        val elapsedHours = (currentTime - enabledTime) / (60 * 60 * 1000)
        val remainingHours = 24 - elapsedHours
        
        return "Debug: ENABLED (${remainingHours}h remaining until auto-disable)"
    }

    private fun createLogFileIfNeeded() {
        val context = contextRef?.get()
        if (isDebugEnabled && context != null) {
            try {
                // Escribir directamente en la raíz del directorio de archivos externos
                val debugDir = context.getExternalFilesDir(null) ?: context.filesDir
                debugDir.mkdirs() // Asegurar que el directorio existe

                logFile = File(debugDir, "kremote_debug.log")

                Timber.d("Debug directory: ${debugDir.absolutePath}")
                Timber.d("Debug file path: ${logFile!!.absolutePath}")
                Timber.d("Directory exists: ${debugDir.exists()}")
                Timber.d("Directory writable: ${debugDir.canWrite()}")

                // Limpiar log anterior si es muy grande (>5MB)
                logFile?.let { file ->
                    if (file.exists() && file.length() > 5 * 1024 * 1024) {
                        // En lugar de borrar, rotar el archivo
                        val backupFile = File(debugDir, "kremote_debug_previous.log")
                        if (backupFile.exists()) {
                            backupFile.delete() // Borrar backup anterior si existe
                        }
                        
                        // Mover el archivo actual como backup
                        file.renameTo(backupFile)
                        Timber.d("Rotated large log file to: ${backupFile.absolutePath}")
                        
                        // Crear nuevo archivo limpio
                        file.createNewFile()
                        Timber.d("Created fresh log file: ${file.absolutePath}")
                    }

                    // Log inicial para verificar que funciona
                    val initialMessage = "\n=== KREMOTE DEBUG SESSION STARTED ===\n" +
                            "Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                            "File: ${file.absolutePath}\n" +
                            "Previous session backup available: ${File(debugDir, "kremote_debug_previous.log").exists()}\n"
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

}