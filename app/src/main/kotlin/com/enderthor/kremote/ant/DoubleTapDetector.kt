package com.enderthor.kremote.ant

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.utils.DebugLogger
import android.os.Looper
import android.os.Handler
import timber.log.Timber

class DoubleTapDetector(
    private var doubleTapTimeout: Long,
    private val onCommand: (GenericCommandNumber, PressType) -> Unit
) {
    private val lastCommandTime = mutableMapOf<GenericCommandNumber, Long>()
    private val pendingCommands = mutableSetOf<GenericCommandNumber>()

    fun handleCommand(commandNumber: GenericCommandNumber) {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastCommandTime[commandNumber] ?: 0L
        val timeSinceLastCommand = currentTime - lastTime

        DebugLogger.logKeyEvent(0, "CMD_$commandNumber", "DETECTING", true)
        Timber.d("Command received: $commandNumber")
        Timber.d("Time since last press: $timeSinceLastCommand")
        Timber.d("Double tap timeout: $doubleTapTimeout")

        if (timeSinceLastCommand <= doubleTapTimeout && timeSinceLastCommand > 50) { // Evitar rebotes < 50ms
            // Double tap detected
            DebugLogger.logKeyEvent(0, "CMD_$commandNumber", "DOUBLE", true)
            Timber.d("Double tap detected: $commandNumber")
            pendingCommands.remove(commandNumber)
            onCommand(commandNumber, PressType.DOUBLE)
        } else {
            // Single tap, wait to confirm
            pendingCommands.add(commandNumber)
            Handler(Looper.getMainLooper()).postDelayed({
                if (pendingCommands.contains(commandNumber)) {
                    pendingCommands.remove(commandNumber)
                    DebugLogger.logKeyEvent(0, "CMD_$commandNumber", "SINGLE", true)
                    onCommand(commandNumber, PressType.SINGLE)
                }
            }, doubleTapTimeout)
        }

        lastCommandTime[commandNumber] = currentTime
    }

    fun updateTimeout(newTimeout: Long) {
        this.doubleTapTimeout = newTimeout
        DebugLogger.logConnectionEvent(0, "DOUBLE_TAP_TIMEOUT_UPDATED", "New timeout: $newTimeout ms")
        Timber.d("[DoubleTapDetector] Timeout updated to: $newTimeout ms")
    }

}