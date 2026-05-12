package com.enderthor.kremote.data

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AntRemoteKeyTest {

    @Test
    fun `byCommand contains every entry`() {
        assertEquals(AntRemoteKey.entries.size, AntRemoteKey.byCommand.size)
    }

    @Test
    fun `byCommand returns same instance as linear scan for every entry`() {
        for (entry in AntRemoteKey.entries) {
            assertSame(entry, AntRemoteKey.byCommand[entry.gCommand])
        }
    }

    @Test
    fun `byCommand returns null for an unmapped GenericCommandNumber`() {
        val mappedCommands = AntRemoteKey.entries.map { it.gCommand }.toSet()
        val unmapped = GenericCommandNumber.entries.firstOrNull { it !in mappedCommands }
        if (unmapped != null) {
            assertNull(AntRemoteKey.byCommand[unmapped])
        }
    }
}
