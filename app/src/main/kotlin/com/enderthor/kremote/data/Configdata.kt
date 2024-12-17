package com.enderthor.kremote.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import io.hammerhead.karooext.models.PerformHardwareAction

enum class KarooKey(val action: Any, val label: String) {
    BOTTOMLEFT(PerformHardwareAction.BottomLeftPress, "Back"),
    BOTTOMRIGHT(PerformHardwareAction.BottomRightPress, "Accept / Navegate in"),
    TOPLEFT(PerformHardwareAction.TopLeftPress, "Page Left"),
    TOPRIGHT(PerformHardwareAction.TopRightPress, "Page Right"),
    CONTROLCENTER(PerformHardwareAction.ControlCenterComboPress, "Control Center"),
    DRAWER(PerformHardwareAction.DrawerActionComboPress, "Combo Left and Right"),
    MAP("Map", "Map")

}

@Serializable
data class RemoteSettings(
    val remoteleft: KarooKey = KarooKey.BOTTOMLEFT,
    val remoteright: KarooKey = KarooKey.BOTTOMRIGHT,
    val remoteup: KarooKey = KarooKey.TOPLEFT,
    val onlyWhileRiding: Boolean = true,
){
    companion object {
        val defaultSettings = Json.encodeToString(RemoteSettings())
    }
}


