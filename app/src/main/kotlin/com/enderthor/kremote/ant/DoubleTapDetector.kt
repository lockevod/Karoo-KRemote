package com.enderthor.kremote.ant

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.PressType
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

        Timber.d("Comando recibido: $commandNumber")
        Timber.d("Tiempo desde la última pulsación: ${currentTime - lastTime}")
        Timber.d("Tiempo de doble pulsación: $doubleTapTimeout")

        if (currentTime - lastTime <= doubleTapTimeout) {

            Timber.d("Doble pulsación detectada: $commandNumber")
            pendingCommands.remove(commandNumber)
            onCommand(commandNumber, PressType.DOUBLE)
        } else {
            pendingCommands.add(commandNumber)
            Handler(Looper.getMainLooper()).postDelayed({
                if (pendingCommands.contains(commandNumber)) {
                    pendingCommands.remove(commandNumber)
                    onCommand(commandNumber, PressType.SINGLE)
                }
            }, doubleTapTimeout)
        }

        lastCommandTime[commandNumber] = currentTime
    }

    fun updateTimeout(newTimeout: Long) {
        this.doubleTapTimeout = newTimeout
        Timber.d("[DoubleTapDetector] Timeout actualizado a: $newTimeout ms")
    }

}