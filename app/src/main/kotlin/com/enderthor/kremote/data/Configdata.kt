package com.enderthor.kremote.data

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import io.hammerhead.karooext.models.PerformHardwareAction

enum class KarooKey(val action: PerformHardwareAction, val label: String) {
    BOTTOMLEFT(PerformHardwareAction.BottomLeftPress, "Back/Lap"),
    BOTTOMRIGHT(PerformHardwareAction.BottomRightPress, "Accept / Navegate In"),
    TOPLEFT(PerformHardwareAction.TopLeftPress, "Page Left"),
    TOPRIGHT(PerformHardwareAction.TopRightPress, "Page Right"),
    CONTROLCENTER(PerformHardwareAction.ControlCenterComboPress, "Control Center"),
    DRAWER(PerformHardwareAction.DrawerActionComboPress, "Mapa/Zoom In"),
}



enum class AntRemoteKey(val label: String, val command: GenericCommandNumber) {
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
data class RemoteKeyMapping(
        val karooKey:KarooKey,
        val remoteKey: AntRemoteKey
)

@Serializable
data class RemoteSettings(
    val remoteleft: KarooKey = KarooKey.TOPLEFT,
    val remoteright: KarooKey = KarooKey.TOPRIGHT,
    val remoteup: KarooKey = KarooKey.BOTTOMLEFT,
    val onlyWhileRiding: Boolean = true,
){
    companion object {
        val defaultSettings = Json.encodeToString(RemoteSettings())
    }
}

@Serializable
data class RemoteSettingsKaroo(
    val remoteleft: RemoteKeyMapping = RemoteKeyMapping(KarooKey.TOPLEFT,AntRemoteKey.LAP),
    val remoteright: RemoteKeyMapping = RemoteKeyMapping(KarooKey.TOPRIGHT,AntRemoteKey.MENU_DOWN),
    val remoteup: RemoteKeyMapping = RemoteKeyMapping(KarooKey.BOTTOMLEFT,AntRemoteKey.UNRECOGNIZED),
    val remotebottom: RemoteKeyMapping = RemoteKeyMapping(KarooKey.BOTTOMRIGHT,AntRemoteKey.MENU_SELECT),
    val totalKeys: Int = 3,
    val onlyWhileRiding: Boolean = true,
){
    companion object {
        val defaultSettings = Json.encodeToString(RemoteSettings())
    }
}




