package com.enderthor.kremote.ant

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.utils.DebugLogger
import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import timber.log.Timber

class DoubleTapDetector(
    private var doubleTapTimeout: Long,
    private var doubleTapEnabled: Boolean = false,
    private val onCommand: (GenericCommandNumber, PressType) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastCommandTime = mutableMapOf<GenericCommandNumber, Long>()
    private val pendingCommands = mutableSetOf<GenericCommandNumber>()
    private val pendingCallbacks = mutableMapOf<GenericCommandNumber, Runnable>()

    /**
     * Thread-safe: toda la lógica de estado se ejecuta en Main thread vía mainHandler.post().
     * Puede llamarse desde cualquier hilo.
     */
    fun handleCommand(commandNumber: GenericCommandNumber) {
        mainHandler.post {
            val currentTime = SystemClock.elapsedRealtime()
            val lastTime = lastCommandTime[commandNumber] ?: 0L
            val timeSinceLastCommand = currentTime - lastTime

            if (!doubleTapEnabled) {
                clearPendingCommand(commandNumber)
                onCommand(commandNumber, PressType.SINGLE)
                lastCommandTime[commandNumber] = currentTime
                return@post
            }

            if (DebugLogger.isEnabled()) {
                Timber.d("[DoubleTap] Command: $commandNumber timeSince=${timeSinceLastCommand}ms")
            }

            if (timeSinceLastCommand <= doubleTapTimeout && timeSinceLastCommand > 50) {
                // Double tap detectado
                if (DebugLogger.isEnabled()) {
                    DebugLogger.logKeyEvent(0, "CMD_$commandNumber", "DOUBLE", true)
                    Timber.d("[DoubleTap] DOUBLE detected: $commandNumber")
                }
                clearPendingCommand(commandNumber)
                onCommand(commandNumber, PressType.DOUBLE)
            } else {
                // Single tap: esperar confirmación
                pendingCommands.add(commandNumber)
                pendingCallbacks[commandNumber]?.let(mainHandler::removeCallbacks)

                val callback = Runnable {
                    if (pendingCommands.contains(commandNumber)) {
                        pendingCommands.remove(commandNumber)
                        pendingCallbacks.remove(commandNumber)
                        if (DebugLogger.isEnabled()) {
                            DebugLogger.logKeyEvent(0, "CMD_$commandNumber", "SINGLE", true)
                        }
                        onCommand(commandNumber, PressType.SINGLE)
                    }
                }
                pendingCallbacks[commandNumber] = callback
                mainHandler.postDelayed(callback, doubleTapTimeout)
            }

            lastCommandTime[commandNumber] = currentTime
        }
    }

    /**
     * Thread-safe: puede llamarse desde cualquier hilo.
     * El cambio se aplica en Main thread para evitar race conditions con handleCommand.
     */
    fun updateTimeout(newTimeout: Long) {
        mainHandler.post {
            doubleTapTimeout = newTimeout
            Timber.d("[DoubleTapDetector] Timeout updated to: ${newTimeout}ms")
        }
    }

    /**
     * Thread-safe: puede llamarse desde cualquier hilo.
     * Cancela callbacks pendientes en Main thread antes de aplicar el cambio.
     */
    fun updateEnabled(enabled: Boolean) {
        mainHandler.post {
            if (doubleTapEnabled == enabled) return@post
            doubleTapEnabled = enabled
            if (!enabled) {
                // Cancelar cualquier single-tap pendiente para que no dispare como SINGLE
                // después de haber desactivado el doble toque
                clearAllPendingCommands()
            }
            Timber.d("[DoubleTapDetector] Double tap enabled: $doubleTapEnabled")
        }
    }

    private fun clearPendingCommand(commandNumber: GenericCommandNumber) {
        pendingCommands.remove(commandNumber)
        pendingCallbacks.remove(commandNumber)?.let(mainHandler::removeCallbacks)
    }

    private fun clearAllPendingCommands() {
        pendingCallbacks.values.forEach(mainHandler::removeCallbacks)
        pendingCallbacks.clear()
        pendingCommands.clear()
    }

}