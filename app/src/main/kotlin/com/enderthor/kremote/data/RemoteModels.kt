package com.enderthor.kremote.data

import kotlinx.serialization.Serializable
import io.hammerhead.karooext.models.PerformHardwareAction
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber


const val EXTENSION_NAME = "kremote"
const val reconnectAttempts = 10
const val reconnectDelayMs = 5000L // 5 segundos entre intentos
const val maxreconnectDelayMs = 60000L // 1 minuto
const val checkIntervalMs=  120000L // 10 segundos
const val autoReconnect = true
const val minReconnectInterval = 2000L

sealed class DeviceMessage {
    data class Error(val message: String) : DeviceMessage()
    data class Success(val message: String) : DeviceMessage()
}


@Serializable
enum class KarooKey(val action: PerformHardwareAction, val label: String) {
    BOTTOMLEFT(PerformHardwareAction.BottomLeftPress, "Back/Lap"),
    BOTTOMRIGHT(PerformHardwareAction.BottomRightPress, "Accept / Navigate In"),
    TOPLEFT(PerformHardwareAction.TopLeftPress, "Page Left"),
    TOPRIGHT(PerformHardwareAction.TopRightPress, "Page Right"),
    CONTROLCENTER(PerformHardwareAction.ControlCenterComboPress, "Control Center"),
    DRAWER(PerformHardwareAction.DrawerActionComboPress, "Map/Zoom In"),
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
    val karooKey: KarooKey? = null // KarooKey asignado al comando
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
)

