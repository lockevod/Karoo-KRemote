package com.enderthor.kremote.extension

import android.content.Intent
import com.dsi.ant.plugins.antplus.pcc.controls.AntPlusGenericControllableDevicePcc.IGenericCommandReceiver
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.GlobalSettings
import com.enderthor.kremote.data.LearnedCommand
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteRepository
import com.enderthor.kremote.data.RemoteType
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val RIDE_APP_OPENED = "io.hammerhead.intent.action.RIDE_APP_OPENED"
private const val RIDE_STOP = "io.hammerhead.hx.intent.action.RIDE_STOP"

/**
 * Drives the real [KremoteExtension] the way the ANT+ plugin would: AntManager keeps the plugin's
 * command receiver in a private field, and a decoded key press is that receiver being called. The
 * plugin AAR itself cannot run here, so everything after the decode is the extension's own code.
 */
internal class KremoteHarness(private val karoo: FakeKarooRule) {
    private val repository = RemoteRepository(karoo.app)

    // Robolectric reuses the DataStore delegate across tests of a class, so wipe what a test left.
    fun resetConfig() = runBlocking {
        repository.getDevices().first().forEach { repository.removeDevice(it.id) }
        repository.updateGlobalSetting { GlobalSettings() }
    }

    fun seed(
        device: RemoteDevice = remote(),
        settings: GlobalSettings = GlobalSettings(onlyWhileRiding = false),
    ) = runBlocking {
        repository.addDevice(device)
        repository.updateGlobalSetting { settings }
    }

    // DataStore edits must not run on the Robolectric test thread once the extension is up, so
    // config changes go through a background dispatcher and the caller waits on the extension.
    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Applies a config change on a background thread and returns once the edit has landed. */
    fun change(block: suspend RemoteRepository.() -> Unit) {
        val job = configScope.launch { block(repository) }
        runBlocking { job.join() }
    }

    fun updateDevice(deviceId: String = "garmin-remote", update: (RemoteDevice) -> RemoteDevice) =
        change { updateDeviceProperty(deviceId, update) }

    fun assign(assignments: List<LearnedCommand>, deviceId: String = "garmin-remote") =
        change { assignKeyCodesToCommands(deviceId, assignments) }

    fun add(device: RemoteDevice) = change { addDevice(device) }

    fun remove(deviceId: String) = change { removeDevice(deviceId) }

    fun activate(deviceId: String) = change { setActiveDevice(deviceId) }

    /** The active device as the extension currently sees it, straight off the key press path. */
    fun activeDevice(): RemoteDevice? = extension.read("activeDevice")

    fun awaitActiveDevice(predicate: (RemoteDevice?) -> Boolean) =
        karoo.awaitValue { true.takeIf { predicate(activeDevice()) } }

    fun awaitDoubleTapEnabled(enabled: Boolean) =
        karoo.awaitValue { true.takeIf { detector().read<Boolean>("doubleTapEnabled") == enabled } }

    fun awaitDoubleTapTimeout(timeout: Long) =
        karoo.awaitValue { true.takeIf { detector().read<Long>("doubleTapTimeout") == timeout } }

    private fun detector(): Any = extension.antManager.read<Any>("doubleTapDetector")!!

    private lateinit var extension: KremoteExtension

    /** Starts the service and waits until the stored config has reached the key press path. */
    fun start(): KremoteExtension {
        karoo.host<KremoteExtension>()
        extension = KremoteExtension.getInstance()!!
        karoo.awaitValue { extension.read<Any>("globalSettings") }
        karoo.awaitValue { extension.read<RemoteDevice>("activeDevice") }
        val detector = extension.antManager.read<Any>("doubleTapDetector")!!
        val wantsDoubleTap = extension.read<RemoteDevice>("activeDevice")!!.enabledDoubleTap
        // The enable flag reaches the detector through a main looper post, which only runs once pumped.
        karoo.awaitValue { true.takeIf { detector.read<Boolean>("doubleTapEnabled") == wantsDoubleTap } }
        // AntManager drops a press within 50 ms of the previous one per device, and the virtual
        // clock starts near zero.
        RobolectricPump.advanceBy(1000.milliseconds)
        return extension
    }

    fun press(command: GenericCommandNumber, after: Duration = 100.milliseconds) {
        RobolectricPump.advanceBy(after)
        val receiver = extension.antManager.read<IGenericCommandReceiver>("mRemoteCommand")!!
        receiver.onNewGenericCommand(0L, null, 0, 0, 0, command)
        RobolectricPump.pumpMainLooper()
    }

    fun rideOpened() = broadcast(RIDE_APP_OPENED)

    fun rideStopped() = broadcast(RIDE_STOP)

    private fun broadcast(action: String) {
        karoo.app.sendBroadcast(Intent(action))
        RobolectricPump.pumpMainLooper()
    }

    fun remote(
        mapping: List<com.enderthor.kremote.data.LearnedCommand> = RemoteDevice.getDefaultLearnedCommands(),
        doubleTap: Boolean = false,
        doubleTapTimeout: Long = com.enderthor.kremote.data.DEFAULT_DOUBLE_TAP_TIMEOUT,
    ) = RemoteDevice(
        id = "garmin-remote",
        name = "Garmin remote",
        type = RemoteType.ANT,
        isActive = true,
        enabledDoubleTap = doubleTap,
        doubleTapTimeout = doubleTapTimeout,
        learnedCommands = mapping.toMutableList(),
    )
}

@Suppress("UNCHECKED_CAST")
internal fun <T> Any.read(name: String): T? =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T?
