package com.enderthor.kremote.extension

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
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RequestAnt
import io.hammerhead.karooext.models.TurnScreenOff
import io.hammerhead.karooext.models.TurnScreenOn
import io.hammerhead.karooext.models.ZoomPage
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Effect families the default mapping does not touch: fast zoom out, forced screen on ordering, bells. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomScreenAndBellTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var remote: KremoteHarness

    @Before
    fun setUp() {
        remote = KremoteHarness(karoo)
        remote.resetConfig()
    }

    private fun dispatched(): List<KarooEffect> = karoo.system.effects.filter { it !is RequestAnt }

    private fun single(command: AntRemoteKey, key: KarooKey?) =
        LearnedCommand(command, PressType.SINGLE, key)

    @Test
    fun `fast zoom out sends three zoom-out steps spaced 150 ms apart`() {
        remote.seed(remote.remote(listOf(single(AntRemoteKey.MENU_UP, KarooKey.ZOOM_OUT_FAST))))
        remote.start()

        remote.press(GenericCommandNumber.MENU_UP)
        assertEquals(listOf(ZoomPage(false)), karoo.system.effectsOf<ZoomPage>())

        RobolectricPump.advanceBy(150.milliseconds)
        assertEquals(2, karoo.system.effectsOf<ZoomPage>().size)

        RobolectricPump.advanceBy(150.milliseconds)
        assertEquals(List(3) { ZoomPage(false) }, karoo.system.effectsOf<ZoomPage>())
    }

    @Test
    fun `forced screen on wakes the screen once before a fast zoom burst`() {
        remote.seed(
            remote.remote(listOf(single(AntRemoteKey.MENU_UP, KarooKey.ZOOM_IN_FAST))),
            GlobalSettings(onlyWhileRiding = false, isForcedScreenOn = true),
        )
        remote.start()

        remote.press(GenericCommandNumber.MENU_UP)

        assertEquals(listOf<KarooEffect>(TurnScreenOn, ZoomPage(true)), dispatched())

        RobolectricPump.advanceBy(300.milliseconds)
        assertEquals(1, karoo.system.effectsOf<TurnScreenOn>().size)
        assertEquals(3, karoo.system.effectsOf<ZoomPage>().size)
    }

    @Test
    fun `forced screen on precedes turning the screen off`() {
        remote.seed(
            remote.remote(
                listOf(
                    single(AntRemoteKey.STOP, KarooKey.TURN_OFF),
                    single(AntRemoteKey.LAP, KarooKey.LAP),
                )
            ),
            GlobalSettings(onlyWhileRiding = false, isForcedScreenOn = true),
        )
        remote.start()

        remote.press(GenericCommandNumber.STOP)
        remote.press(GenericCommandNumber.LAP)

        assertEquals(
            listOf<KarooEffect>(TurnScreenOn, TurnScreenOff, TurnScreenOn, MarkLap),
            dispatched(),
        )
    }

    @Test
    fun `bell 3 rings the BELL5 pattern`() {
        remote.seed(remote.remote(listOf(single(AntRemoteKey.LENGTH, KarooKey.BELL3))))
        remote.start()

        remote.press(GenericCommandNumber.LENGTH)

        val beep = karoo.awaitEffect<PlayBeepPattern>()
        assertEquals(BellBeepPattern.BELL5.tones, beep.tones)
    }
}
