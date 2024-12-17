package com.enderthor.kremote.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import io.hammerhead.karooext.models.PerformHardwareAction

enum class KarooKey(val action: String, val label: String) {
    BOTTOMLEFT(PerformHardwareAction.BottomLeftPress.toString(), "Back"),
    BOTTOMRIGHT(PerformHardwareAction.BottomRightPress.toString(), "Accept / Navegate In"),
    TOPLEFT(PerformHardwareAction.TopLeftPress.toString(), "Page Left"),
    TOPRIGHT(PerformHardwareAction.TopRightPress.toString(), "Page Right"),
    CONTROLCENTER(PerformHardwareAction.ControlCenterComboPress.toString(), "Control Center"),
    DRAWER(PerformHardwareAction.DrawerActionComboPress.toString(), "Combo Left/Right"),
    //MAP("Map", "Show Map")

}

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



