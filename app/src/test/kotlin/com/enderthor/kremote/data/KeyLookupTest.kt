package com.enderthor.kremote.data

import com.dsi.ant.plugins.antplus.pcc.controls.defines.GenericCommandNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyLookupTest {

    @Test
    fun `EMPTY returns null for any query`() {
        assertNull(KeyLookup.EMPTY.get(GenericCommandNumber.LAP, PressType.SINGLE))
        assertNull(KeyLookup.EMPTY.get(GenericCommandNumber.LAP, PressType.DOUBLE))
        assertEquals(0, KeyLookup.EMPTY.size)
    }

    @Test
    fun `get returns SINGLE mapping when present`() {
        val lookup = KeyLookup(
            single = mapOf(GenericCommandNumber.LAP to KarooKey.LAP),
            double = emptyMap()
        )
        assertEquals(KarooKey.LAP, lookup.get(GenericCommandNumber.LAP, PressType.SINGLE))
    }

    @Test
    fun `get returns DOUBLE mapping when present`() {
        val lookup = KeyLookup(
            single = emptyMap(),
            double = mapOf(GenericCommandNumber.LAP to KarooKey.ZOOM_OUT)
        )
        assertEquals(KarooKey.ZOOM_OUT, lookup.get(GenericCommandNumber.LAP, PressType.DOUBLE))
    }

    @Test
    fun `SINGLE and DOUBLE for the same command are independent`() {
        val lookup = KeyLookup(
            single = mapOf(GenericCommandNumber.LAP to KarooKey.LAP),
            double = mapOf(GenericCommandNumber.LAP to KarooKey.ZOOM_OUT)
        )
        assertEquals(KarooKey.LAP, lookup.get(GenericCommandNumber.LAP, PressType.SINGLE))
        assertEquals(KarooKey.ZOOM_OUT, lookup.get(GenericCommandNumber.LAP, PressType.DOUBLE))
    }

    @Test
    fun `get returns null when press type has no mapping`() {
        val lookup = KeyLookup(
            single = mapOf(GenericCommandNumber.LAP to KarooKey.LAP),
            double = emptyMap()
        )
        assertNull(lookup.get(GenericCommandNumber.LAP, PressType.DOUBLE))
    }

    @Test
    fun `size sums SINGLE and DOUBLE entries`() {
        val lookup = KeyLookup(
            single = mapOf(
                GenericCommandNumber.LAP to KarooKey.LAP,
                GenericCommandNumber.MENU_UP to KarooKey.TOPRIGHT
            ),
            double = mapOf(GenericCommandNumber.LAP to KarooKey.ZOOM_OUT)
        )
        assertEquals(3, lookup.size)
    }
}
