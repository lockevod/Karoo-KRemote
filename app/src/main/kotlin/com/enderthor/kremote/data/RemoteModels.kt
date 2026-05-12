package com.enderthor.kremote.data

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.Serializable
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.KarooEffect
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.R
import io.hammerhead.karooext.models.MarkLap
import io.hammerhead.karooext.models.PauseRide
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.ResumeRide
import io.hammerhead.karooext.models.ShowMapPage
import io.hammerhead.karooext.models.TurnScreenOff
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.ZoomPage


const val EXTENSION_NAME = "kremote"
const val autoReconnect = true
const val minReconnectInterval = 2000L
const val DEFAULT_DOUBLE_TAP_TIMEOUT = 1200L

// Debug logging configuration
const val DEBUG_LOGGING_ENABLED = false

// Performance optimizations
const val COMMAND_PROCESSING_DELAY_MS = 50L // Minimum delay between commands


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
enum class BellBeepPattern(val tones: List<PlayBeepPattern.Tone>) {

    BELL4(
        listOf(
        PlayBeepPattern.Tone(3_800, 900),
        PlayBeepPattern.Tone(0, 300),
        PlayBeepPattern.Tone(3_800, 1000),
    )),
    BELL5(
        listOf(
            PlayBeepPattern.Tone(3_550, 900),
            PlayBeepPattern.Tone(0, 300),
            PlayBeepPattern.Tone(3_550, 1000),
        )),
}


@Serializable
enum class KarooKey(val action: KarooEffect, val labelResId: Int) {
    BOTTOMRIGHT(PerformHardwareAction.BottomRightPress, R.string.karoo_key_bottomright),
    BOTTOMLEFT(PerformHardwareAction.BottomLeftPress, R.string.karoo_key_bottomleft),
    CONTROLCENTER(PerformHardwareAction.ControlCenterComboPress, R.string.karoo_key_controlcenter),
    BELL2(PlayBeepPattern(BellBeepPattern.BELL4.tones), R.string.karoo_key_bell2),
    BELL3(PlayBeepPattern(BellBeepPattern.BELL5.tones), R.string.karoo_key_bell3),
    DRAWER(PerformHardwareAction.DrawerActionComboPress, R.string.karoo_key_drawer),
    LAP(MarkLap, R.string.karoo_key_lap),
    SHOWMAP(ShowMapPage(true), R.string.karoo_key_showmap),
    TOPLEFT(PerformHardwareAction.TopLeftPress, R.string.karoo_key_topleft),
    TOPRIGHT(PerformHardwareAction.TopRightPress, R.string.karoo_key_topright),
    PAUSE(PauseRide, R.string.karoo_key_pause),
    RESUME(ResumeRide, R.string.karoo_key_resume),
    TURN_ON(TurnScreenOn, R.string.karoo_key_turnon),
    TURN_OFF(TurnScreenOff, R.string.karoo_key_turnoff),
    ZOOM_IN(ZoomPage(true), R.string.karoo_key_zoomin),
    ZOOM_OUT(ZoomPage(false), R.string.karoo_key_zoomout),
    ZOOM_IN_FAST(ZoomPage(true), R.string.karoo_key_zoomin_fast),
    ZOOM_OUT_FAST(ZoomPage(false), R.string.karoo_key_zoomout_fast);

}

@Serializable
enum class AntRemoteKey(val labelResId: Int, val gCommand: GenericCommandNumber) {
    MENU_DOWN(R.string.ant_key_menu_down, GenericCommandNumber.MENU_DOWN),
    MENU_UP(R.string.ant_key_menu_up, GenericCommandNumber.MENU_UP),
    LENGTH(R.string.ant_key_length, GenericCommandNumber.LENGTH),
    MENU_BACK(R.string.ant_key_menu_back, GenericCommandNumber.MENU_BACK),
    MENU_SELECT(R.string.ant_key_menu_select, GenericCommandNumber.MENU_SELECT),
    RESET(R.string.ant_key_reset, GenericCommandNumber.RESET),
    HOME(R.string.ant_key_home, GenericCommandNumber.HOME),
    LAP(R.string.ant_key_lap, GenericCommandNumber.LAP),
    START(R.string.ant_key_start, GenericCommandNumber.START),
    STOP(R.string.ant_key_stop, GenericCommandNumber.STOP),
    UNRECOGNIZED(R.string.ant_key_unrecognized, GenericCommandNumber.UNRECOGNIZED);

    @Composable
    fun getLabel(): String = stringResource(id = labelResId)

    companion object {
        // Hot-path O(1) lookup: avoids linear scan on every ANT command.
        val byCommand: Map<GenericCommandNumber, AntRemoteKey> = entries.associateBy { it.gCommand }
    }
}

fun KarooKey.getLabelString(context: android.content.Context): String {
    return context.getString(labelResId)
}

fun AntRemoteKey.getLabelString(context: android.content.Context): String {
    return context.getString(labelResId)
}

@Serializable
data class LearnedCommand(
    val command: AntRemoteKey,
    val pressType: PressType = PressType.SINGLE,
    val karooKey: KarooKey? = null
)

/**
 * Precomputed O(1) lookup for the hot ANT-command path.
 * Two maps split by PressType so each press does a single HashMap.get without
 * allocating a Pair or comparing enums in a linear scan.
 */
class KeyLookup(
    private val single: Map<GenericCommandNumber, KarooKey>,
    private val double: Map<GenericCommandNumber, KarooKey>
) {
    fun get(command: GenericCommandNumber, pressType: PressType): KarooKey? = when (pressType) {
        PressType.SINGLE -> single[command]
        PressType.DOUBLE -> double[command]
    }

    val size: Int get() = single.size + double.size

    companion object {
        val EMPTY = KeyLookup(emptyMap(), emptyMap())
    }
}


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

    /**
     * Precomputed O(1) lookup table for the hot command path.
     * Split by pressType to avoid Pair allocations on every button press.
     * Caller should rebuild whenever learnedCommands changes (i.e. on active device updates).
     */
    fun buildKeyLookup(): KeyLookup {
        val single = HashMap<GenericCommandNumber, KarooKey>(learnedCommands.size)
        val double = HashMap<GenericCommandNumber, KarooKey>(learnedCommands.size)
        for (lc in learnedCommands) {
            val key = lc.karooKey ?: continue
            when (lc.pressType) {
                PressType.SINGLE -> single[lc.command.gCommand] = key
                PressType.DOUBLE -> double[lc.command.gCommand] = key
            }
        }
        return KeyLookup(single, double)
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
