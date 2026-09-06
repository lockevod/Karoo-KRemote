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

            val (isDouble, newLastTime) = decideDouble(lastTime, currentTime, doubleTapTimeout)

            if (isDouble) {
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

            lastCommandTime[commandNumber] = newLastTime
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

    companion object {
        /** Dos eventos ANT más juntos que esto son rebote de la misma pulsación, no un doble. */
        internal const val MIN_TAP_GAP_MS = 50L

        /**
         * Decide si una pulsación cierra un doble toque, y con qué marca de tiempo queda el
         * botón para la siguiente.
         *
         * La clave es el segundo valor: al emitir DOUBLE el par se **consume** (`0L`). Antes
         * se guardaba siempre `now`, así que una 3ª pulsación dentro del timeout formaba otro
         * doble con la 2ª: un mando repitiendo a ~4 Hz contra un timeout de 1200 ms escupía
         * DOUBLE tras DOUBLE — pausar/reanudar la ruta, marcar vueltas o apagar la pantalla
         * en cadena. Consumir el par lo reduce a uno cada dos pulsaciones, que es el techo de
         * un detector sin estado; separar "mantener pulsado" de "pulsar rápido" pediría
         * semántica de hold. (El rebote de la propia radio ya lo filtra MIN_TAP_GAP_MS.)
         *
         * Pura y sin dependencias de Android a propósito: es la única parte de este detector
         * que se puede probar en la JVM (el resto vive sobre un Handler del looper principal).
         */
        internal fun decideDouble(lastTime: Long, now: Long, timeout: Long): Pair<Boolean, Long> {
            val elapsed = now - lastTime
            val isDouble = lastTime > 0L && elapsed <= timeout && elapsed > MIN_TAP_GAP_MS
            return isDouble to if (isDouble) 0L else now
        }
    }
}