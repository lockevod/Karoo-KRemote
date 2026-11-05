package com.enderthor.kremote.extension

import android.content.Intent
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import com.enderthor.kremote.data.GlobalSettings
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.RequestAnt
import io.hammerhead.karooext.models.TurnScreenOn
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the only-while-riding gate suppresses, including the side effects it must not leak. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RideGatingTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var remote: KremoteHarness

    @Before
    fun setUp() {
        remote = KremoteHarness(karoo)
        remote.resetConfig()
    }

    private fun dispatched(): List<KarooEffect> = karoo.system.effects.filter { it !is RequestAnt }

    @Test
    fun `a press blocked by only-while-riding forwards nothing, not even the forced screen on`() {
        remote.seed(settings = GlobalSettings(onlyWhileRiding = true, isForcedScreenOn = true))
        remote.start()

        val mark = karoo.system.effects.size
        remote.press(GenericCommandNumber.MENU_DOWN)

        karoo.assertNoEffect<TurnScreenOn>(after = mark)
        karoo.assertNoEffect<PerformHardwareAction>(after = mark)
        assertEquals(emptyList<KarooEffect>(), dispatched())
    }

    @Test
    fun `with only-while-riding off a press works before any ride starts`() {
        remote.seed(settings = GlobalSettings(onlyWhileRiding = false))
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)

        assertEquals(listOf<KarooEffect>(PerformHardwareAction.TopRightPress), dispatched())
    }

    @Test
    fun `an unrelated broadcast does not open the ride gate`() {
        remote.seed(settings = GlobalSettings(onlyWhileRiding = true))
        remote.start()

        karoo.app.sendBroadcast(Intent("com.example.NOT_A_RIDE"))
        RobolectricPump.pumpMainLooper()

        remote.press(GenericCommandNumber.MENU_DOWN)

        assertEquals(emptyList<KarooEffect>(), dispatched())
    }
}
