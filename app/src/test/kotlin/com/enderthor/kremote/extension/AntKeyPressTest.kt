package com.enderthor.kremote.extension

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.BellBeepPattern
import com.enderthor.kremote.data.GlobalSettings
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.LearnedCommand
import com.enderthor.kremote.data.PressType
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.MarkLap
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RequestAnt
import io.hammerhead.karooext.models.ShowMapPage
import io.hammerhead.karooext.models.TurnScreenOff
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.ZoomPage
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AntKeyPressTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var remote: KremoteHarness

    @Before
    fun setUp() {
        remote = KremoteHarness(karoo)
        remote.resetConfig()
    }

    /** What the extension asked the Karoo to do for the rider, without its own radio request. */
    private fun dispatched(): List<KarooEffect> = karoo.system.effects.filter { it !is RequestAnt }

    private fun single(command: AntRemoteKey, key: KarooKey?) =
        LearnedCommand(command, PressType.SINGLE, key)

    @Test
    fun `the default mapping sends the mapped Karoo key`() {
        remote.seed()
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        assertEquals(listOf<KarooEffect>(PerformHardwareAction.TopRightPress), dispatched())

        remote.press(GenericCommandNumber.LAP)
        assertEquals(
            listOf(PerformHardwareAction.TopRightPress, PerformHardwareAction.BottomLeftPress),
            dispatched(),
        )

        remote.press(GenericCommandNumber.UNRECOGNIZED)
        assertEquals(ShowMapPage(true), dispatched().last())
    }

    @Test
    fun `each assignable Karoo key dispatches the effect it stands for`() {
        val assignments = listOf(
            AntRemoteKey.MENU_DOWN to KarooKey.BOTTOMRIGHT,
            AntRemoteKey.MENU_UP to KarooKey.CONTROLCENTER,
            AntRemoteKey.LENGTH to KarooKey.BELL2,
            AntRemoteKey.MENU_BACK to KarooKey.DRAWER,
            AntRemoteKey.MENU_SELECT to KarooKey.LAP,
            AntRemoteKey.RESET to KarooKey.PAUSE,
            AntRemoteKey.HOME to KarooKey.RESUME,
            AntRemoteKey.START to KarooKey.TURN_ON,
            AntRemoteKey.STOP to KarooKey.TURN_OFF,
            AntRemoteKey.LAP to KarooKey.ZOOM_OUT,
            AntRemoteKey.UNRECOGNIZED to KarooKey.TOPLEFT,
        )
        remote.seed(remote.remote(assignments.map { (button, key) -> single(button, key) }))
        remote.start()

        assignments.forEach { (button, _) -> remote.press(button.gCommand) }

        assertEquals(assignments.map { (_, key) -> key.action }, dispatched())
    }

    @Test
    fun `a button without an assignment does nothing`() {
        remote.seed(remote.remote(listOf(single(AntRemoteKey.MENU_DOWN, null))))
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        remote.press(GenericCommandNumber.START)

        assertEquals(emptyList<KarooEffect>(), dispatched())
    }

    @Test
    fun `only while riding holds presses until the ride app opens and again after it stops`() {
        remote.seed(settings = GlobalSettings(onlyWhileRiding = true))
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        assertEquals(emptyList<KarooEffect>(), dispatched())

        remote.rideOpened()
        remote.press(GenericCommandNumber.MENU_DOWN)
        assertEquals(listOf<KarooEffect>(PerformHardwareAction.TopRightPress), dispatched())

        remote.rideStopped()
        remote.press(GenericCommandNumber.MENU_DOWN)
        assertEquals(1, dispatched().size)
    }

    @Test
    fun `a bounce within 50 ms counts as one press`() {
        remote.seed()
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        remote.press(GenericCommandNumber.MENU_DOWN, after = 20.milliseconds)
        assertEquals(1, dispatched().size)

        remote.press(GenericCommandNumber.MENU_DOWN, after = 60.milliseconds)
        assertEquals(2, dispatched().size)
    }

    @Test
    fun `forced screen on wakes the screen before the action`() {
        remote.seed(settings = GlobalSettings(onlyWhileRiding = false, isForcedScreenOn = true))
        remote.start()

        remote.press(GenericCommandNumber.LAP)

        assertEquals(
            listOf<KarooEffect>(TurnScreenOn, PerformHardwareAction.BottomLeftPress),
            dispatched(),
        )
    }

    @Test
    fun `turning the screen off is dispatched as is when forced screen on is off`() {
        remote.seed(remote.remote(listOf(single(AntRemoteKey.STOP, KarooKey.TURN_OFF), single(AntRemoteKey.LAP, KarooKey.LAP))))
        remote.start()

        remote.press(GenericCommandNumber.STOP)
        remote.press(GenericCommandNumber.LAP)

        assertEquals(listOf<KarooEffect>(TurnScreenOff, MarkLap), dispatched())
    }

    @Test
    fun `fast zoom sends three zoom steps spaced 150 ms apart`() {
        remote.seed(remote.remote(listOf(single(AntRemoteKey.MENU_UP, KarooKey.ZOOM_IN_FAST))))
        remote.start()

        remote.press(GenericCommandNumber.MENU_UP)
        assertEquals(1, karoo.system.effectsOf<ZoomPage>().size)

        RobolectricPump.advanceBy(150.milliseconds)
        assertEquals(2, karoo.system.effectsOf<ZoomPage>().size)

        RobolectricPump.advanceBy(150.milliseconds)
        assertEquals(List(3) { ZoomPage(true) }, karoo.system.effectsOf<ZoomPage>())
    }

    @Test
    fun `a bell falls back to the SDK beep when the mute bypass has no HAL to talk to`() {
        remote.seed(
            remote.remote(listOf(single(AntRemoteKey.LENGTH, KarooKey.BELL2))),
            GlobalSettings(onlyWhileRiding = false, bypassMute = true),
        )
        remote.start()

        remote.press(GenericCommandNumber.LENGTH)

        val beep = karoo.awaitEffect<PlayBeepPattern>()
        assertEquals(BellBeepPattern.BELL4.tones, beep.tones)
    }

    @Test
    fun `with double tap on a double press sends only the double mapping`() {
        remote.seed(remote.remote(doubleTap = true))
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        remote.press(GenericCommandNumber.MENU_DOWN, after = 200.milliseconds)
        // Past the single timeout the first press must not fire on its own.
        RobolectricPump.advanceBy(2000.milliseconds)

        assertEquals(listOf<KarooEffect>(ZoomPage(true)), dispatched())
    }

    @Test
    fun `with double tap on a lone press fires as single only after the timeout`() {
        remote.seed(remote.remote(doubleTap = true, doubleTapTimeout = 800))
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        RobolectricPump.advanceBy(700.milliseconds)
        assertEquals(emptyList<KarooEffect>(), dispatched())

        RobolectricPump.advanceBy(200.milliseconds)
        assertEquals(listOf<KarooEffect>(PerformHardwareAction.TopRightPress), dispatched())
    }

    @Test
    fun `learning mode reports the button to the settings screen instead of acting on it`() {
        remote.seed()
        remote.start()
        val state = karoo.app.getSharedPreferences("kremote_state", Context.MODE_PRIVATE)
        val learned = karoo.app.getSharedPreferences("kremote_learned_commands", Context.MODE_PRIVATE)

        state.edit().putBoolean("learning_mode", true).putLong("learning_mode_ts", System.currentTimeMillis()).commit()
        RobolectricPump.pumpMainLooper()
        remote.press(GenericCommandNumber.LAP)

        assertEquals("LAP", karoo.awaitValue { learned.getString("last_command", null) })
        assertEquals(emptyList<KarooEffect>(), dispatched())

        state.edit().putBoolean("learning_mode", false).putLong("learning_mode_ts", System.currentTimeMillis()).commit()
        RobolectricPump.pumpMainLooper()
        remote.press(GenericCommandNumber.LAP)

        assertEquals(listOf<KarooEffect>(PerformHardwareAction.BottomLeftPress), dispatched())
    }

    @Test
    fun `a learning flag left over from a dead settings screen does not mute the remote`() {
        remote.seed()
        karoo.app.getSharedPreferences("kremote_state", Context.MODE_PRIVATE).edit()
            .putBoolean("learning_mode", true).putLong("learning_mode_ts", 0L).commit()
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)

        assertTrue(dispatched().single() == PerformHardwareAction.TopRightPress)
    }
}
