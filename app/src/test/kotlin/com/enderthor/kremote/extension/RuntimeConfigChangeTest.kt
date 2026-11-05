package com.enderthor.kremote.extension

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.LearnedCommand
import com.enderthor.kremote.data.PressType
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.data.RemoteType
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.MarkLap
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.RequestAnt
import io.hammerhead.karooext.models.ZoomPage
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Config edits that land while the extension runs must reach the key press path without a restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeConfigChangeTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var remote: KremoteHarness

    @Before
    fun setUp() {
        remote = KremoteHarness(karoo)
        remote.resetConfig()
    }

    private fun dispatched(): List<KarooEffect> = karoo.system.effects.filter { it !is RequestAnt }

    @Test
    fun `a mapping changed while running is used for the next press`() {
        remote.seed()
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        assertEquals(listOf<KarooEffect>(PerformHardwareAction.TopRightPress), dispatched())

        remote.assign(listOf(LearnedCommand(AntRemoteKey.MENU_DOWN, PressType.SINGLE, KarooKey.LAP)))
        remote.awaitActiveDevice {
            it?.getKarooKey(GenericCommandNumber.MENU_DOWN, PressType.SINGLE) == KarooKey.LAP
        }

        remote.press(GenericCommandNumber.MENU_DOWN)
        assertEquals(
            listOf<KarooEffect>(PerformHardwareAction.TopRightPress, MarkLap),
            dispatched(),
        )
    }

    @Test
    fun `double tap turned on while running starts pairing presses`() {
        remote.seed()
        remote.start()

        remote.updateDevice { it.copy(enabledDoubleTap = true) }
        remote.awaitDoubleTapEnabled(true)

        remote.press(GenericCommandNumber.MENU_DOWN)
        remote.press(GenericCommandNumber.MENU_DOWN, after = 200.milliseconds)
        RobolectricPump.advanceBy(1300.milliseconds)

        assertEquals(listOf<KarooEffect>(ZoomPage(true)), dispatched())
    }

    @Test
    fun `a new double tap timeout while running is honoured`() {
        remote.seed(remote.remote(doubleTap = true, doubleTapTimeout = 1200))
        remote.start()

        remote.updateDevice { it.copy(doubleTapTimeout = 400) }
        remote.awaitDoubleTapTimeout(400)

        remote.press(GenericCommandNumber.MENU_DOWN)
        RobolectricPump.advanceBy(250.milliseconds)
        assertEquals(emptyList<KarooEffect>(), dispatched())

        RobolectricPump.advanceBy(200.milliseconds)
        assertEquals(listOf<KarooEffect>(PerformHardwareAction.TopRightPress), dispatched())
    }

    @Test
    fun `removing the active device while running stops dispatching`() {
        remote.seed()
        remote.start()

        remote.remove("garmin-remote")
        remote.awaitActiveDevice { it == null }

        remote.press(GenericCommandNumber.MENU_DOWN)

        assertEquals(emptyList<KarooEffect>(), dispatched())
    }

    @Test
    fun `switching the active device while running swaps the mapping`() {
        remote.seed()
        remote.start()

        val second = RemoteDevice(
            id = "second",
            name = "Second",
            type = RemoteType.ANT,
            learnedCommands = mutableListOf(
                LearnedCommand(AntRemoteKey.MENU_DOWN, PressType.SINGLE, KarooKey.BOTTOMLEFT)
            ),
        )
        remote.add(second)
        remote.activate("second")
        remote.awaitActiveDevice { it?.id == "second" }

        remote.press(GenericCommandNumber.MENU_DOWN)

        assertEquals(listOf<KarooEffect>(PerformHardwareAction.BottomLeftPress), dispatched())
    }
}
