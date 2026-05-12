package com.enderthor.kremote.data

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteDeviceBuildKeyLookupTest {

    private fun device(commands: List<LearnedCommand>) = RemoteDevice(
        id = "test",
        name = "Test",
        type = RemoteType.ANT,
        learnedCommands = commands.toMutableList()
    )

    @Test
    fun `empty learnedCommands yields empty lookup`() {
        val lookup = device(emptyList()).buildKeyLookup()
        assertEquals(0, lookup.size)
        assertNull(lookup.get(GenericCommandNumber.LAP, PressType.SINGLE))
    }

    @Test
    fun `commands with null karooKey are skipped`() {
        val lookup = device(
            listOf(
                LearnedCommand(AntRemoteKey.LAP, PressType.SINGLE, karooKey = null),
                LearnedCommand(AntRemoteKey.MENU_UP, PressType.SINGLE, karooKey = KarooKey.TOPRIGHT)
            )
        ).buildKeyLookup()

        assertEquals(1, lookup.size)
        assertNull(lookup.get(GenericCommandNumber.LAP, PressType.SINGLE))
        assertEquals(KarooKey.TOPRIGHT, lookup.get(GenericCommandNumber.MENU_UP, PressType.SINGLE))
    }

    @Test
    fun `SINGLE and DOUBLE for the same command coexist in the lookup`() {
        val lookup = device(
            listOf(
                LearnedCommand(AntRemoteKey.LAP, PressType.SINGLE, KarooKey.LAP),
                LearnedCommand(AntRemoteKey.LAP, PressType.DOUBLE, KarooKey.ZOOM_OUT)
            )
        ).buildKeyLookup()

        assertEquals(KarooKey.LAP, lookup.get(GenericCommandNumber.LAP, PressType.SINGLE))
        assertEquals(KarooKey.ZOOM_OUT, lookup.get(GenericCommandNumber.LAP, PressType.DOUBLE))
    }

    @Test
    fun `lookup result matches legacy getKarooKey for every learned command`() {
        val device = device(RemoteDevice.getDefaultLearnedCommands())
        val lookup = device.buildKeyLookup()

        for (lc in device.learnedCommands) {
            val expected = device.getKarooKey(lc.command.gCommand, lc.pressType)
            val actual = lookup.get(lc.command.gCommand, lc.pressType)
            assertEquals(
                "Mismatch for ${lc.command} (${lc.pressType})",
                expected,
                actual
            )
        }
    }

    @Test
    fun `later duplicate mapping for same command+pressType wins`() {
        // The two existing data structures (Map and learnedCommands.find) differ:
        // - getKarooKey returns the FIRST match
        // - buildKeyLookup map.put overwrites, so it keeps the LAST match
        // This documents that behaviour so any future divergence is caught.
        val lookup = device(
            listOf(
                LearnedCommand(AntRemoteKey.LAP, PressType.SINGLE, KarooKey.LAP),
                LearnedCommand(AntRemoteKey.LAP, PressType.SINGLE, KarooKey.TOPRIGHT)
            )
        ).buildKeyLookup()

        assertEquals(KarooKey.TOPRIGHT, lookup.get(GenericCommandNumber.LAP, PressType.SINGLE))
    }
}
