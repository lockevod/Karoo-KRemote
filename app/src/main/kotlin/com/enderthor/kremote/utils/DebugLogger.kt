package com.enderthor.kremote.utils

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*
import java.lang.ref.WeakReference

object DebugLogger {
    @Volatile private var isDebugEnabled = false
    private var logFile: File? = null
    private var contextRef: WeakReference<Context>? = null

    // --- Async writer pipeline ---
    // Hot-path callers (Main handler, ANT binder thread) only pay an allocation-light,
    // non-blocking trySend into this bounded channel. A single writer coroutine confined
    // to Dispatchers.IO owns the file handle, the SimpleDateFormat and the size check.
    private const val LOG_QUEUE_CAPACITY = 512
    private const val ROTATION_CHECK_EVERY = 256
    private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L
    private const val TAIL_READ_BYTES = 256 * 1024L

    // A log entry carries the raw message plus the timestamp captured at enqueue time,
    // so the formatting cost is paid by the writer thread, not the caller.
    private data class LogEntry(val timeMillis: Long, val message: String)

    private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var logChannel: Channel<LogEntry>? = null
    @Volatile private var writerJob: kotlinx.coroutines.Job? = null

    // Automatic timeout configuration for debug (24 hours)
    private const val DEBUG_AUTO_DISABLE_TIMEOUT = 24 * 60 * 60 * 1000L // 24 hours in ms
    private const val PREFS_NAME = "kremote_debug_prefs"
    private const val PREF_DEBUG_ENABLED = "debug_enabled"
    private const val PREF_DEBUG_ENABLED_TIME = "debug_enabled_time"

    fun initialize(context: Context, enabled: Boolean = false) {
        // Use ApplicationContext to avoid memory leaks
        this.contextRef = WeakReference(context.applicationContext)
        
        // Check if there's a saved preference and if it hasn't expired
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedEnabled = prefs.getBoolean(PREF_DEBUG_ENABLED, false)
        val enabledTime = prefs.getLong(PREF_DEBUG_ENABLED_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        
        // If debug was enabled but timeout has passed, disable it
        isDebugEnabled = if (savedEnabled && (currentTime - enabledTime) < DEBUG_AUTO_DISABLE_TIMEOUT) {
            Timber.w("Debug logging restored from previous session (${(currentTime - enabledTime) / (60 * 60 * 1000)}h ago)")
            true
        } else {
            if (savedEnabled) {
                // Clear expired preference using KTX
                prefs.edit { clear() }
                Timber.i("Debug logging auto-disabled due to timeout")
            }
            enabled // Use default value
        }
        
        createLogFileIfNeeded()
    }

    fun setEnabled(enabled: Boolean) {
        val context = contextRef?.get()
        isDebugEnabled = enabled
        
        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (enabled) {
                // Save preference with timestamp using KTX
                prefs.edit {
                    putBoolean(PREF_DEBUG_ENABLED, true)
                    putLong(PREF_DEBUG_ENABLED_TIME, System.currentTimeMillis())
                }

                createLogFileIfNeeded()
                logConnectionEvent(0, "DEBUG_MODE_ENABLED", "Debug logging activated from menu (will auto-disable in 24h)")
                Timber.w("⚠️ DEBUG LOGGING ENABLED - This may impact performance! Auto-disable in 24h")
            } else {
                // Clear preference using KTX
                prefs.edit { clear() }
                logConnectionEvent(0, "DEBUG_MODE_DISABLED", "Debug logging deactivated")
                Timber.i("Debug logging disabled")
                // Flush and stop the async writer now that logging is off. The DISABLED
                // entry above was enqueued while isDebugEnabled was still true, so stopWriter
                // drains it before tearing down.
                stopWriter()
            }
        }
    }
    
    /**
     * Obtains information about the debug status and when it expires
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
                // Write directly to external files directory root
                val debugDir = context.getExternalFilesDir(null) ?: context.filesDir
                debugDir.mkdirs() // Ensure the directory exists

                logFile = File(debugDir, "kremote_debug.log")

                Timber.d("Debug directory: ${debugDir.absolutePath}")
                Timber.d("Debug file path: ${logFile!!.absolutePath}")
                Timber.d("Directory exists: ${debugDir.exists()}")
                Timber.d("Directory writable: ${debugDir.canWrite()}")

                // Clear previous log if it's too large (>5MB)
                logFile?.let { file ->
                    if (file.exists() && file.length() > 5 * 1024 * 1024) {
                        // Instead of deleting, rotate the file
                        val backupFile = File(debugDir, "kremote_debug_previous.log")
                        if (backupFile.exists()) {
                            backupFile.delete() // Delete previous backup if exists
                        }
                        
                        // Move the current file as backup
                        file.renameTo(backupFile)
                        Timber.d("Rotated large log file to: ${backupFile.absolutePath}")
                        
                        // Create new clean file
                        file.createNewFile()
                        Timber.d("Created fresh log file: ${file.absolutePath}")
                    }

                    // Initial log to verify it's working
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
                // Fallback to internal directory if there are issues
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
            // Find the first frame that is not DebugLogger
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
        // Allocation-light, non-blocking enqueue. Timestamp is captured here (cheap) but
        // formatted later on the writer thread. trySend never blocks; on overflow the
        // channel drops the oldest entry (BufferOverflow.DROP_OLDEST) so the hot path is
        // never stalled by slow I/O.
        val channel = ensureWriterStarted()
        channel.trySend(LogEntry(System.currentTimeMillis(), message))

        // Mirror to Timber (cheap, in-memory) preserving previous behaviour.
        Timber.d(message)
    }

    /**
     * Lazily starts the single writer coroutine and returns the live channel. Idempotent and
     * cheap on the fast path (one volatile read). Synchronized only on the (rare) start path.
     */
    private fun ensureWriterStarted(): Channel<LogEntry> {
        logChannel?.let { return it }
        synchronized(this) {
            logChannel?.let { return it }
            val channel = Channel<LogEntry>(
                capacity = LOG_QUEUE_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            logChannel = channel
            writerJob = loggerScope.launch { runWriter(channel) }
            return channel
        }
    }

    private suspend fun runWriter(channel: Channel<LogEntry>) {
        // Single-threaded writer: one reused formatter and a persistent BufferedWriter.
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val reusableDate = Date()
        var writer: BufferedWriter? = null
        var openFile: File? = null
        var writesSinceCheck = 0

        fun closeWriter() {
            try { writer?.flush() } catch (_: Exception) {}
            try { writer?.close() } catch (_: Exception) {}
            writer = null
            openFile = null
        }

        try {
            for (entry in channel) {
                val file = logFile ?: continue

                // (Re)open the writer if the target file changed (e.g. after rotation/clear).
                if (writer == null || openFile != file) {
                    closeWriter()
                    try {
                        writer = BufferedWriter(FileWriter(file, /* append = */ true))
                        openFile = file
                    } catch (e: Exception) {
                        Timber.e(e, "Error opening debug log writer")
                        continue
                    }
                }

                reusableDate.time = entry.timeMillis
                val line = "[${formatter.format(reusableDate)}] ${entry.message}\n"
                try {
                    writer?.write(line)
                } catch (e: Exception) {
                    Timber.e(e, "Error writing to debug log file")
                    closeWriter()
                    continue
                }

                // Flush when the queue momentarily drains so readers see recent entries.
                if (channel.isEmpty) {
                    try { writer?.flush() } catch (_: Exception) {}
                }

                // Periodic rotation check so a long debug session can't grow past the cap.
                if (++writesSinceCheck >= ROTATION_CHECK_EVERY) {
                    writesSinceCheck = 0
                    try {
                        if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                            closeWriter()
                            rotateLogFile(file)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error rotating debug log file")
                    }
                }
            }
        } finally {
            closeWriter()
        }
    }

    /** Moves an oversized log to a single backup slot and recreates a fresh file. */
    private fun rotateLogFile(file: File) {
        val parent = file.parentFile
        if (parent != null) {
            val backupFile = File(parent, "kremote_debug_previous.log")
            if (backupFile.exists()) backupFile.delete()
            file.renameTo(backupFile)
        }
        file.createNewFile()
        Timber.d("Rotated large log file (writer): ${file.absolutePath}")
    }

    /** Stops the writer, draining and flushing whatever is queued. Safe to call when stopped. */
    private fun stopWriter() {
        val channel = logChannel ?: return
        synchronized(this) {
            logChannel = null
            channel.close()
            try {
                runBlocking { writerJob?.join() }
            } catch (e: Exception) {
                Timber.e(e, "Error stopping debug log writer")
            }
            writerJob = null
        }
    }

    suspend fun getLogContent(): String = withContext(Dispatchers.IO) {
        val file = logFile?.takeIf { it.exists() } ?: return@withContext "No debug log available"
        try {
            val length = file.length()
            if (length <= TAIL_READ_BYTES) {
                file.readText()
            } else {
                // Bound memory: only read the tail of the file.
                RandomAccessFile(file, "r").use { raf ->
                    val start = length - TAIL_READ_BYTES
                    raf.seek(start)
                    val bytes = ByteArray(TAIL_READ_BYTES.toInt())
                    raf.readFully(bytes)
                    "…(truncated, showing last ${TAIL_READ_BYTES / 1024}KB)…\n" + String(bytes, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error reading debug log content")
            "Error reading debug log"
        }
    }

    fun clearLog() {
        try {
            // Drop queued entries and flush/close the writer so it doesn't race the truncate.
            stopWriter()
            logFile?.let { file ->
                if (file.exists()) {
                    // Empty the file content instead of deleting it
                    file.writeText("")

                    // Write a confirmation message
                    val clearMessage = "\n=== LOG CLEARED ===\n" +
                            "Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                            "File: ${file.absolutePath}\n\n"
                    file.appendText(clearMessage)

                    Timber.d("[DebugLogger] ✅ Log cleaned")
                } else {
                    Timber.w("[DebugLogger] ⚠️ Log file does not exist, cannot clean")
                }
            } ?: run {
                Timber.w("[DebugLogger] ⚠️ LogFile is null, cannot clean")
            }
        } catch (e: Exception) {
            Timber.e(e, "[DebugLogger] ❌ Error cleaning the log")
        }
    }

}