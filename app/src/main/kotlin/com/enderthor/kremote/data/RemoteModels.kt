package com.enderthor.kremote.data

import kotlinx.serialization.Serializable
import io.hammerhead.karooext.models.PerformHardwareAction
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber


sealed class DeviceMessage {
    data class Error(val message: String) : DeviceMessage()
    data class Success(val message: String) : DeviceMessage()
}


@Serializable
enum class KarooKey(val action: PerformHardwareAction, val label: String) {
    BOTTOMLEFT(PerformHardwareAction.BottomLeftPress, "Back"),
    BOTTOMRIGHT(PerformHardwareAction.BottomRightPress, "Accept / Navigate In"),
    TOPLEFT(PerformHardwareAction.TopLeftPress, "Page Left"),
    TOPRIGHT(PerformHardwareAction.TopRightPress, "Page Right"),
    CONTROLCENTER(PerformHardwareAction.ControlCenterComboPress, "Control Center"),
    DRAWER(PerformHardwareAction.DrawerActionComboPress, "Map/Zoom In"),
}

@Serializable
enum class AntRemoteKey(val label: String, val gCommand: GenericCommandNumber) {
    MENU_DOWN("Menu Down", GenericCommandNumber.MENU_DOWN),
    MENU_UP("Menu Up", GenericCommandNumber.MENU_UP),
    LENGTH("Function Down", GenericCommandNumber.LENGTH),
    MENU_BACK("Menu Back", GenericCommandNumber.MENU_BACK),
    MENU_SELECT("Menu Select", GenericCommandNumber.MENU_SELECT),
    RESET("Reset", GenericCommandNumber.RESET),
    HOME("Home", GenericCommandNumber.HOME),
    LAP("Lap", GenericCommandNumber.LAP),
    START("Start", GenericCommandNumber.START),
    STOP("Stop", GenericCommandNumber.STOP),
    UNRECOGNIZED("Unrecognized", GenericCommandNumber.UNRECOGNIZED)
}

@Serializable
data class LearnedCommand(
    val command: AntRemoteKey,
    val karooKey: KarooKey? = null // KarooKey asignado al comando
)


@Serializable
enum class RemoteType {
    ANT
}

const val EXTENSION_NAME = "kremote"

@Serializable
data class RemoteDevice(
    val id: String,
    val name: String,
    val type: RemoteType,
    val antDeviceId: Int? = null,
    val macAddress: String? = null,
    val isActive: Boolean = false,
    val learnedCommands: MutableList<LearnedCommand> = mutableListOf()
) {
    fun getKarooKey(command: GenericCommandNumber): KarooKey? {
        return learnedCommands.find { it.command.gCommand == command }?.karooKey
    }

    companion object {
        fun getDefaultLearnedCommands(): List<LearnedCommand> {
            return listOf(
                LearnedCommand(command = AntRemoteKey.MENU_DOWN),
                LearnedCommand(command = AntRemoteKey.LAP),
                LearnedCommand(command = AntRemoteKey.UNRECOGNIZED)
            )
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
    val autoReconnect: Boolean = true,
    val reconnectAttempts: Int = 5,
    val reconnectDelayMs: Long = 5000 // 5 segundos entre intentos
)