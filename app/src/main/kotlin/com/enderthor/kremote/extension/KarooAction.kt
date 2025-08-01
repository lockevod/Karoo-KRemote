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
import com.enderthor.kremote.utils.DebugLogger
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

        // LOGGING DETALLADO DEL ESTADO DE NAVEGACIÓN
        DebugLogger.logConnectionEvent(
            deviceNumber = activeDeviceInfo?.antDeviceId ?: 0,
            event = "COMMAND_RECEIVED",
            details = "Command: $commandNumber, PressType: ${pressType.name}, Connected: $isConnected, Riding: $currentlyRiding, OnlyWhileRiding: $onlyDuringRide",
            source = "KarooAction"
        )

        Timber.d("🔍 [KRemote] DIAGNÓSTICO COMPLETO:")
        Timber.d("   ├── Servicio conectado: $isConnected")
        Timber.d("   ├── En modo riding: $currentlyRiding")
        Timber.d("   ├── Solo durante carrera: $onlyDuringRide")
        Timber.d("   ├── Dispositivo activo: ${activeDeviceInfo?.name ?: "NINGUNO"}")
        Timber.d("   └── Comando: $commandNumber")

        if (!isConnected) {
            Timber.w("❌ [KRemote] BLOQUEADO: Servicio Karoo no conectado")
            DebugLogger.logError("EXECUTION_BLOCKED", "Karoo service not connected - command blocked", source = "KarooAction")
            return
        }

        if (!currentlyRiding && onlyDuringRide) {
            Timber.w("❌ [KRemote] BLOQUEADO: No está en carrera y configurado para 'solo durante carrera'")
            DebugLogger.logConnectionEvent(
                deviceNumber = activeDeviceInfo?.antDeviceId ?: 0,
                event = "EXECUTION_BLOCKED_NOT_RIDING",
                details = "Not in ride mode but configured for 'only while riding'",
                source = "KarooAction"
            )
            return
        }

        // LOGGING: Verificar que podemos proceder
        DebugLogger.logConnectionEvent(
            deviceNumber = activeDeviceInfo?.antDeviceId ?: 0,
            event = "EXECUTION_ALLOWED",
            details = "All conditions met - proceeding with command processing",
            source = "KarooAction"
        )

        try {
            val antRemoteKey = AntRemoteKey.entries.find { it.gCommand == commandNumber }
            if (antRemoteKey == null) {
                Timber.w("❌ [KRemote] Comando ANT+ no reconocido: $commandNumber")
                DebugLogger.logError("MAPPING", "Unrecognized ANT+ command: $commandNumber", source = "KarooAction")
                return
            }

            DebugLogger.logKeyEvent(
                deviceNumber = activeDeviceInfo?.antDeviceId ?: 0,
                command = antRemoteKey.name,
                pressType = pressType.name,
                processed = false,
                source = "KarooAction"
            )

            activeDeviceInfo?.let { device ->
                Timber.d("🔍 [KRemote] Buscando mapeo para: ${antRemoteKey.getLabelString(context)} (${if (pressType == PressType.DOUBLE) "DOBLE" else "SIMPLE"})")
                DebugLogger.logConnectionEvent(
                    deviceNumber = device.antDeviceId ?: 0,
                    event = "COMMAND_MAPPING_SEARCH",
                    details = "Looking for mapping: ${antRemoteKey.name} (${pressType.name})",
                    source = "KarooAction"
                )

                val karooKey = device.getKarooKey(antRemoteKey.gCommand, pressType)
                if (karooKey != null) {
                    Timber.d("✅ [KRemote] EJECUTANDO: ${antRemoteKey.getLabelString(context)} → ${karooKey.getLabelString(context)}")
                    DebugLogger.logConnectionEvent(
                        deviceNumber = device.antDeviceId ?: 0,
                        event = "COMMAND_MAPPED_SUCCESS",
                        details = "Mapping found: ${antRemoteKey.name} → ${karooKey.action::class.simpleName}",
                        source = "KarooAction"
                    )
                    executeKarooAction(karooKey.action)
                    DebugLogger.logKeyEvent(
                        deviceNumber = device.antDeviceId ?: 0,
                        command = antRemoteKey.name,
                        pressType = pressType.name,
                        processed = true,
                        source = "KarooAction"
                    )
                } else {
                    Timber.w("❌ [KRemote] NO MAPEADO: No hay acción asignada para ${antRemoteKey.getLabelString(context)} (${if (pressType == PressType.DOUBLE) "DOBLE" else "SIMPLE"})")
                    DebugLogger.logConnectionEvent(
                        deviceNumber = device.antDeviceId ?: 0,
                        event = "COMMAND_NOT_MAPPED",
                        details = "No mapping found for: ${antRemoteKey.name} (${pressType.name}). Available mappings: ${device.learnedCommands.size}",
                        source = "KarooAction"
                    )
                    Timber.d("🔍 [KRemote] Comandos disponibles en dispositivo:")
                    device.learnedCommands.forEach { cmd ->
                        Timber.d("   └── ${cmd.command.getLabelString(context)} (${cmd.pressType}) → ${cmd.karooKey?.getLabelString(context) ?: "SIN ASIGNAR"}")
                    }
                }
            } ?: run {
                Timber.w("❌ [KRemote] FALTA DISPOSITIVO: No hay dispositivo activo configurado")
                DebugLogger.logError("MAPPING", "No active device configured for command mapping", source = "KarooAction")
            }
        } catch (e: Exception) {
            Timber.e(e, "💥 [KRemote] ERROR en handleAntCommand")
            DebugLogger.logError("MAPPING", "Error in handleAntCommand for command $commandNumber", e, "KarooAction")
        }
    }
    fun executeKarooAction(action: KarooEffect) {
        Timber.d("executeKarooAction: $action")
        DebugLogger.logConnectionEvent(
            deviceNumber = 0,
            event = "KAROO_ACTION_START",
            details = "Executing Karoo action: ${action::class.simpleName}",
            source = "KarooAction"
        )

        if (!isServiceConnected()) {
            Timber.w("No se puede ejecutar acción: servicio Karoo no conectado")
            DebugLogger.logError("KAROO_EXECUTION", "Cannot execute action - Karoo service not connected", source = "KarooAction")
            return
        }

        try {
            if(isForcedScreenOn()) {
                DebugLogger.logConnectionEvent(0, "SCREEN_ON_FORCED", "Turning screen on before action", "KarooAction")
                karooSystem.dispatch(TurnScreenOn)
            }

            DebugLogger.logConnectionEvent(
                deviceNumber = 0,
                event = "KAROO_ACTION_DISPATCHED",
                details = "Action dispatched to Karoo system: ${action::class.simpleName}",
                source = "KarooAction"
            )
            karooSystem.dispatch(action)
            
            DebugLogger.logConnectionEvent(
                deviceNumber = 0,
                event = "KAROO_ACTION_SUCCESS",
                details = "Action executed successfully: ${action::class.simpleName}",
                source = "KarooAction"
            )

        } catch (e: Exception) {
            Timber.e(e, "Error ejecutando acción Karoo: $action")
            DebugLogger.logError("KAROO_EXECUTION", "Error executing Karoo action: ${action::class.simpleName}", e, "KarooAction")
        }
    }
}