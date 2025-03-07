package com.enderthor.kremote.data

import kotlinx.serialization.Serializable
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.KarooEffect
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import io.hammerhead.karooext.models.MarkLap
import io.hammerhead.karooext.models.PauseRide
import io.hammerhead.karooext.models.ResumeRide
import io.hammerhead.karooext.models.ShowMapPage
import io.hammerhead.karooext.models.TurnScreenOff
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.ZoomPage


const val EXTENSION_NAME = "kremote"
const val reconnectAttempts = 10
const val reconnectDelayMs = 5000L
const val maxreconnectDelayMs = 60000L
const val checkIntervalMs=  120000L
const val autoReconnect = true
const val minReconnectInterval = 2000L
const val DEFAULT_DOUBLE_TAP_TIMEOUT = 1200L


@Serializable
enum class PressType {
    SINGLE,
    DOUBLE
}

sealed class DeviceMessage {
    data class Error(val message: String) : DeviceMessage()
    data class Success(val message: String) : DeviceMessage()
}


@Serializable
enum class KarooKey(val action: KarooEffect, val label: String) {
    BOTTOMRIGHT(PerformHardwareAction.BottomRightPress, "Accept/Navigate In"),
    BOTTOMLEFT(PerformHardwareAction.BottomLeftPress, "Back/Lap"),
    CONTROLCENTER(PerformHardwareAction.ControlCenterComboPress, "Control Center"),
    DRAWER(PerformHardwareAction.DrawerActionComboPress, "Drawer"),
    LAP(MarkLap, "Lap"),
    SHOWMAP(ShowMapPage(true), "Map and Zoom In"),
    TOPLEFT(PerformHardwareAction.TopLeftPress, "Page Left"),
    TOPRIGHT(PerformHardwareAction.TopRightPress, "Page Right"),
    PAUSE(PauseRide, "Pause Ride"),
    RESUME(ResumeRide, "Resume Ride"),
    TURN_ON(TurnScreenOn, "Screen On"),
    TURN_OFF(TurnScreenOff, "Screen Off"),
    ZOOM_IN(ZoomPage(true), "Zoom In"),
    ZOOM_OUT(ZoomPage(false), "Zoom Out"),
}

@Serializable
enum class AntRemoteKey(val label: String, val gCommand: GenericCommandNumber) {
    MENU_DOWN("Right", GenericCommandNumber.MENU_DOWN),
    MENU_UP("Menu Up", GenericCommandNumber.MENU_UP),
    LENGTH("Function Down", GenericCommandNumber.LENGTH),
    MENU_BACK("Menu Back", GenericCommandNumber.MENU_BACK),
    MENU_SELECT("Menu Select", GenericCommandNumber.MENU_SELECT),
    RESET("Reset", GenericCommandNumber.RESET),
    HOME("Home", GenericCommandNumber.HOME),
    LAP("Lap", GenericCommandNumber.LAP),
    START("Start", GenericCommandNumber.START),
    STOP("Stop", GenericCommandNumber.STOP),
    UNRECOGNIZED("Up", GenericCommandNumber.UNRECOGNIZED)
}

@Serializable
data class LearnedCommand(
    val command: AntRemoteKey,
    val pressType: PressType = PressType.SINGLE,
    val karooKey: KarooKey? = null
)


@Serializable
enum class RemoteType {
    ANT
}


@Serializable
data class RemoteDevice(
    val id: String,
    val name: String,
    val type: RemoteType,
    val antDeviceId: Int? = null,
    val macAddress: String? = null,
    val isActive: Boolean = false,
    val enabledDoubleTap: Boolean = false,
    val doubleTapTimeout: Long = DEFAULT_DOUBLE_TAP_TIMEOUT,
    val learnedCommands: MutableList<LearnedCommand> = mutableListOf()
) {
    fun getKarooKey(command: GenericCommandNumber, pressType: PressType = PressType.SINGLE): KarooKey? {
        return learnedCommands.find {
            it.command.gCommand == command && it.pressType == pressType
        }?.karooKey
    }

    companion object {


        fun getDefaultLearnedCommands(): List<LearnedCommand> {
            val defaultCommands = mutableListOf<LearnedCommand>()


            defaultCommands.add(LearnedCommand(command = AntRemoteKey.MENU_DOWN, pressType = PressType.SINGLE, karooKey = KarooKey.TOPRIGHT))
            defaultCommands.add(LearnedCommand(command = AntRemoteKey.LAP, pressType = PressType.SINGLE, karooKey = KarooKey. BOTTOMLEFT))
            defaultCommands.add(LearnedCommand(command = AntRemoteKey.UNRECOGNIZED, pressType = PressType.SINGLE, karooKey = KarooKey.SHOWMAP))
            defaultCommands.add(LearnedCommand(command = AntRemoteKey.MENU_DOWN, pressType = PressType.DOUBLE, karooKey = KarooKey.ZOOM_IN))
            defaultCommands.add(LearnedCommand(command = AntRemoteKey.LAP, pressType = PressType.DOUBLE, karooKey = KarooKey.ZOOM_OUT))
            defaultCommands.add(LearnedCommand(command = AntRemoteKey.UNRECOGNIZED, pressType = PressType.DOUBLE, karooKey = KarooKey.CONTROLCENTER))

            return defaultCommands
        }
    }
}

@Serializable
data class GlobalConfig(
    val devices: List<RemoteDevice> = emptyList(),
    val globalSettings: GlobalSettings = GlobalSettings()
)

@Serializable
data class GlobalSettings(
    val onlyWhileRiding: Boolean = true,
    val isForcedScreenOn: Boolean = false,
)

