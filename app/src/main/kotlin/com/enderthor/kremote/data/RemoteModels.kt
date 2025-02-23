package com.enderthor.kremote.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.hammerhead.karooext.models.PerformHardwareAction

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
enum class RemoteType {
    ANT
}

const val EXTENSION_NAME = "kremote"

@Serializable
data class RemoteDevice(
    val id: String,  // UUID para BLE, ANT ID para ANT+
    val name: String,
    val type: RemoteType,
    val antDeviceId: Int? = null,
    val macAddress: String? = null,
    val isActive: Boolean = false,
    val keyMappings: RemoteSettings = RemoteSettings()
)

@Serializable
data class RemoteSettings(
    val remoteleft: KarooKey = KarooKey.TOPLEFT,
    val remoteright: KarooKey = KarooKey.TOPRIGHT,
    val remoteup: KarooKey = KarooKey.BOTTOMLEFT,
) {
    companion object {
        val defaultSettings = Json.encodeToString(RemoteSettings())
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