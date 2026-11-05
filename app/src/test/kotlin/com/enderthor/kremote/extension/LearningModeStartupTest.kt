package com.enderthor.kremote.extension

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.RequestAnt
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the stored learning flag is read at extension startup, including the stale-flag guard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LearningModeStartupTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var remote: KremoteHarness
    private val state get() = karoo.app.getSharedPreferences("kremote_state", Context.MODE_PRIVATE)
    private val learned
        get() = karoo.app.getSharedPreferences("kremote_learned_commands", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        remote = KremoteHarness(karoo)
        remote.resetConfig()
    }

    private fun dispatched(): List<KarooEffect> = karoo.system.effects.filter { it !is RequestAnt }

    @Test
    fun `a fresh learning flag at startup routes presses to the settings app`() {
        remote.seed()
        state.edit().putBoolean("learning_mode", true)
            .putLong("learning_mode_ts", System.currentTimeMillis()).commit()

        remote.start()
        remote.press(GenericCommandNumber.MENU_DOWN)

        assertEquals("MENU_DOWN", karoo.awaitValue { learned.getString("last_command", null) })
        assertEquals(emptyList<KarooEffect>(), dispatched())
    }

    @Test
    fun `a stale learning flag is ignored at startup and re-arms when a new timestamp arrives`() {
        remote.seed()
        state.edit().putBoolean("learning_mode", true).putLong("learning_mode_ts", 0L).commit()
        remote.start()

        remote.press(GenericCommandNumber.MENU_DOWN)
        assertEquals(listOf<KarooEffect>(PerformHardwareAction.TopRightPress), dispatched())

        // The boolean is already true, so only the timestamp changes and only that key notifies.
        state.edit().putLong("learning_mode_ts", System.currentTimeMillis()).commit()
        RobolectricPump.pumpMainLooper()

        remote.press(GenericCommandNumber.MENU_DOWN)
        assertEquals("MENU_DOWN", karoo.awaitValue { learned.getString("last_command", null) })
        assertEquals(1, dispatched().size)
    }
}
