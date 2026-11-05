package com.enderthor.kremote.extension

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.AntRemoteKey
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.LearnedCommand
import com.enderthor.kremote.data.PressType
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.KarooEffect
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

/** Pairing rules of the double tap detector as the key press path experiences them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoubleTapBehaviorTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var remote: KremoteHarness

    @Before
    fun setUp() {
        remote = KremoteHarness(karoo)
        remote.resetConfig()
    }

    private fun dispatched(): List<KarooEffect> = karoo.system.effects.filter { it !is RequestAnt }

    @Test
    fun `double tap sends the double mapping for the button`() {
        remote.seed(remote.remote(doubleTap = true))
        remote.start()

        remote.press(GenericCommandNumber.LAP)
        remote.press(GenericCommandNumber.LAP, after = 200.milliseconds)
        RobolectricPump.advanceBy(1300.milliseconds)

        assertEquals(listOf<KarooEffect>(ZoomPage(false)), dispatched())
    }

    @Test
    fun `two different buttons do not pair into a double`() {
        remote.seed(remote.remote(doubleTap = true))
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        remote.press(GenericCommandNumber.LAP, after = 200.milliseconds)
        RobolectricPump.advanceBy(1400.milliseconds)

        assertEquals(
            listOf<KarooEffect>(PerformHardwareAction.TopRightPress, PerformHardwareAction.BottomLeftPress),
            dispatched(),
        )
    }

    @Test
    fun `after a double the pair is consumed and the next press starts fresh`() {
        remote.seed(remote.remote(doubleTap = true))
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        remote.press(GenericCommandNumber.MENU_DOWN, after = 200.milliseconds)
        assertEquals(listOf<KarooEffect>(ZoomPage(true)), dispatched())

        remote.press(GenericCommandNumber.MENU_DOWN, after = 200.milliseconds)
        RobolectricPump.advanceBy(1300.milliseconds)

        assertEquals(
            listOf<KarooEffect>(ZoomPage(true), PerformHardwareAction.TopRightPress),
            dispatched(),
        )
    }

    @Test
    fun `a button mapped only for double does nothing on a lone press`() {
        remote.seed(
            remote.remote(
                listOf(LearnedCommand(AntRemoteKey.LAP, PressType.DOUBLE, KarooKey.ZOOM_OUT)),
                doubleTap = true,
            )
        )
        remote.start()

        remote.press(GenericCommandNumber.LAP)
        RobolectricPump.advanceBy(1300.milliseconds)

        assertEquals(emptyList<KarooEffect>(), dispatched())
    }
}
