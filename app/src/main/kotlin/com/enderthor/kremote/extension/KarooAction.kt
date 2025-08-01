package com.enderthor.kremote.extension

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.getLabelString
import com.enderthor.kremote.data.KarooKey
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.ZoomPage
import android.content.Context
import com.enderthor.kremote.utils.DebugLogger
import timber.log.Timber
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // DETAILED LOGGING OF NAVIGATION STATE
        DebugLogger.logConnectionEvent(
            deviceNumber = activeDeviceInfo?.antDeviceId ?: 0,
            event = "COMMAND_RECEIVED",
            details = "Command: $commandNumber, PressType: ${pressType.name}, Connected: $isConnected, Riding: $currentlyRiding, OnlyWhileRiding: $onlyDuringRide",
            source = "KarooAction"
        )

        Timber.d("🔍 [KRemote] COMPLETE DIAGNOSTIC:")
        Timber.d("   ├── Service connected: $isConnected")
        Timber.d("   ├── In riding mode: $currentlyRiding")
        Timber.d("   ├── Only during ride: $onlyDuringRide")
        Timber.d("   ├── Active device: ${activeDeviceInfo?.name ?: "NONE"}")
        Timber.d("   └── Command: $commandNumber")

        if (!isConnected) {
            Timber.w("❌ [KRemote] BLOCKED: Karoo service not connected")
            DebugLogger.logError("EXECUTION_BLOCKED", "Karoo service not connected - command blocked", source = "KarooAction")
            return
        }

        if (!currentlyRiding && onlyDuringRide) {
            Timber.w("❌ [KRemote] BLOCKED: Not in ride and configured for 'only while riding'")
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
                Timber.w("❌ [KRemote] Unrecognized ANT+ command: $commandNumber")
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
                Timber.d("🔍 [KRemote] Looking for mapping: ${antRemoteKey.getLabelString(context)} (${if (pressType == PressType.DOUBLE) "DOUBLE" else "SINGLE"})")
                DebugLogger.logConnectionEvent(
                    deviceNumber = device.antDeviceId ?: 0,
                    event = "COMMAND_MAPPING_SEARCH",
                    details = "Looking for mapping: ${antRemoteKey.name} (${pressType.name})",
                    source = "KarooAction"
                )

                val karooKey = device.getKarooKey(antRemoteKey.gCommand, pressType)
                if (karooKey != null) {
                    Timber.d("✅ [KRemote] EXECUTING: ${antRemoteKey.getLabelString(context)} → ${karooKey.getLabelString(context)}")
                    DebugLogger.logConnectionEvent(
                        deviceNumber = device.antDeviceId ?: 0,
                        event = "COMMAND_MAPPED_SUCCESS",
                        details = "Mapping found: ${antRemoteKey.name} → ${karooKey.action::class.simpleName}",
                        source = "KarooAction"
                    )
                    executeKarooAction(karooKey)
                    DebugLogger.logKeyEvent(
                        deviceNumber = device.antDeviceId ?: 0,
                        command = antRemoteKey.name,
                        pressType = pressType.name,
                        processed = true,
                        source = "KarooAction"
                    )
                } else {
                    Timber.w("❌ [KRemote] NOT MAPPED: No action assigned for ${antRemoteKey.getLabelString(context)} (${if (pressType == PressType.DOUBLE) "DOUBLE" else "SINGLE"})")
                    DebugLogger.logConnectionEvent(
                        deviceNumber = device.antDeviceId ?: 0,
                        event = "COMMAND_NOT_MAPPED",
                        details = "No mapping found for: ${antRemoteKey.name} (${pressType.name}). Available mappings: ${device.learnedCommands.size}",
                        source = "KarooAction"
                    )
                    Timber.d("🔍 [KRemote] Available commands on device:")
                    device.learnedCommands.forEach { cmd ->
                        Timber.d("   └── ${cmd.command.getLabelString(context)} (${cmd.pressType}) → ${cmd.karooKey?.getLabelString(context) ?: "UNASSIGNED"}")
                    }
                }
            } ?: run {
                Timber.w("❌ [KRemote] MISSING DEVICE: No active device configured")
                DebugLogger.logError("MAPPING", "No active device configured for command mapping", source = "KarooAction")
            }
        } catch (e: Exception) {
            Timber.e(e, "💥 [KRemote] ERROR in handleAntCommand")
            DebugLogger.logError("MAPPING", "Error in handleAntCommand for command $commandNumber", e, "KarooAction")
        }
    }
    fun executeKarooAction(karooKey: KarooKey) {
        Timber.d("executeKarooAction: $karooKey")
        DebugLogger.logConnectionEvent(
            deviceNumber = 0,
            event = "KAROO_ACTION_START",
            details = "Executing Karoo action: ${karooKey.name}",
            source = "KarooAction"
        )

        if (!isServiceConnected()) {
            Timber.w("Cannot execute action: Karoo service not connected")
            DebugLogger.logError("KAROO_EXECUTION", "Cannot execute action - Karoo service not connected", source = "KarooAction")
            return
        }

        try {
            if(isForcedScreenOn()) {
                DebugLogger.logConnectionEvent(0, "SCREEN_ON_FORCED", "Turning screen on before action", "KarooAction")
                karooSystem.dispatch(TurnScreenOn)
            }

            // Handle special fast zoom actions
            when (karooKey) {
                KarooKey.ZOOM_IN_FAST -> {
                    Timber.d("🔥 [KRemote] EXECUTING FAST ZOOM IN (3x)")
                    DebugLogger.logConnectionEvent(0, "FAST_ZOOM_IN_START", "Executing 3x zoom in", "KarooAction")

                    // Execute 3 consecutive zoom ins with delay between them
                    CoroutineScope(Dispatchers.Main).launch {
                        repeat(3) { i ->
                            karooSystem.dispatch(ZoomPage(true))
                            Timber.d("   └── Zoom In ${i + 1}/3 executed")
                            if (i < 2) delay(150) // 150ms delay between zooms (except last one)
                        }
                    }
                    DebugLogger.logConnectionEvent(0, "FAST_ZOOM_IN_SUCCESS", "3x zoom in completed", "KarooAction")
                }

                KarooKey.ZOOM_OUT_FAST -> {
                    Timber.d("🔥 [KRemote] EXECUTING FAST ZOOM OUT (3x)")
                    DebugLogger.logConnectionEvent(0, "FAST_ZOOM_OUT_START", "Executing 3x zoom out", "KarooAction")

                    // Execute 3 consecutive zoom outs with delay between them
                    CoroutineScope(Dispatchers.Main).launch {
                        repeat(3) { i ->
                            karooSystem.dispatch(ZoomPage(false))
                            Timber.d("   └── Zoom Out ${i + 1}/3 executed")
                            if (i < 2) delay(150) // 150ms delay between zooms (except last one)
                        }
                    }
                    DebugLogger.logConnectionEvent(0, "FAST_ZOOM_OUT_SUCCESS", "3x zoom out completed", "KarooAction")
                }

                else -> {
                    // Execute normal action
                    DebugLogger.logConnectionEvent(
                        deviceNumber = 0,
                        event = "KAROO_ACTION_DISPATCHED",
                        details = "Action dispatched to Karoo system: ${karooKey.action::class.simpleName}",
                        source = "KarooAction"
                    )
                    karooSystem.dispatch(karooKey.action)

                    DebugLogger.logConnectionEvent(
                        deviceNumber = 0,
                        event = "KAROO_ACTION_SUCCESS",
                        details = "Action executed successfully: ${karooKey.action::class.simpleName}",
                        source = "KarooAction"
                    )
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Error executing Karoo action: $karooKey")
            DebugLogger.logError("KAROO_EXECUTION", "Error executing Karoo action: ${karooKey.name}", e, "KarooAction")
        }
    }
}