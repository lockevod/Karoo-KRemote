package com.enderthor.kremote.extension

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.getLabelString
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.KarooEffect
import android.content.Context
import timber.log.Timber

class KarooAction(
    private val karooSystem: KarooSystemService,
    private val context: Context,
    private val isServiceConnected: () -> Boolean,
    private val isRiding: () -> Boolean,
    private val onlyWhileRiding: () -> Boolean,
    private val isForcedScreenOn: () -> Boolean,
    private val activeDevice: () -> RemoteDevice?
) {


    fun handleAntCommand(
        commandNumber: GenericCommandNumber,
        pressType: PressType = PressType.SINGLE
    ) {
        val isConnected = isServiceConnected()
        val currentlyRiding = isRiding()
        val onlyDuringRide = onlyWhileRiding()
        val activeDeviceInfo = activeDevice()

        Timber.d("🔍 [KRemote] DIAGNÓSTICO COMPLETO:")
        Timber.d("   ├── Servicio conectado: $isConnected")
        Timber.d("   ├── En modo riding: $currentlyRiding")
        Timber.d("   ├── Solo durante carrera: $onlyDuringRide")
        Timber.d("   ├── Dispositivo activo: ${activeDeviceInfo?.name ?: "NINGUNO"}")
        Timber.d("   └── Comando: $commandNumber")

        if (!isConnected) {
            Timber.w("❌ [KRemote] BLOQUEADO: Servicio Karoo no conectado")
            return
        }

        if (!currentlyRiding && onlyDuringRide) {
            Timber.w("❌ [KRemote] BLOQUEADO: No está en carrera y configurado para 'solo durante carrera'")
            return
        }

        try {
            val antRemoteKey = AntRemoteKey.entries.find { it.gCommand == commandNumber }
            if (antRemoteKey == null) {
                Timber.w("❌ [KRemote] Comando ANT+ no reconocido: $commandNumber")
                return
            }

            activeDeviceInfo?.let { device ->
                Timber.d("🔍 [KRemote] Buscando mapeo para: ${antRemoteKey.getLabelString(context)} (${if (pressType == PressType.DOUBLE) "DOBLE" else "SIMPLE"})")

                val karooKey = device.getKarooKey(antRemoteKey.gCommand, pressType)
                if (karooKey != null) {
                    Timber.d("✅ [KRemote] EJECUTANDO: ${antRemoteKey.getLabelString(context)} → ${karooKey.getLabelString(context)}")
                    executeKarooAction(karooKey.action)
                } else {
                    Timber.w("❌ [KRemote] NO MAPEADO: No hay acción asignada para ${antRemoteKey.getLabelString(context)} (${if (pressType == PressType.DOUBLE) "DOBLE" else "SIMPLE"})")
                    Timber.d("🔍 [KRemote] Comandos disponibles en dispositivo:")
                    device.learnedCommands.forEach { cmd ->
                        Timber.d("   └── ${cmd.command.getLabelString(context)} (${cmd.pressType}) → ${cmd.karooKey?.getLabelString(context) ?: "SIN ASIGNAR"}")
                    }
                }
            } ?: run {
                Timber.w("❌ [KRemote] FALTA DISPOSITIVO: No hay dispositivo activo configurado")
            }
        } catch (e: Exception) {
            Timber.e(e, "💥 [KRemote] ERROR en handleAntCommand")
        }
    }
    fun executeKarooAction(action: KarooEffect) {
        Timber.d("executeKarooAction: $action")

        if (!isServiceConnected()) {
            Timber.w("No se puede ejecutar acción: servicio Karoo no conectado")
            return
        }

        try {

            if(isForcedScreenOn()) karooSystem.dispatch(TurnScreenOn)

            karooSystem.dispatch(action)

        } catch (e: Exception) {
            Timber.e(e, "Error ejecutando acción Karoo: $action")
        }
    }
}