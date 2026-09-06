package com.enderthor.kremote.ant

import com.enderthor.kremote.ant.DoubleTapDetector.Companion.decideDouble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TIMEOUT = 1200L

class DoubleTapDecisionTest {

    @Test
    fun `first press is never a double`() {
        val (isDouble, newLast) = decideDouble(lastTime = 0L, now = 800L, timeout = TIMEOUT)
        assertFalse(isDouble)
        assertEquals(800L, newLast)
    }

    @Test
    fun `second press inside the timeout is a double`() {
        val (isDouble, _) = decideDouble(lastTime = 1_000L, now = 1_500L, timeout = TIMEOUT)
        assertTrue(isDouble)
    }

    @Test
    fun `second press after the timeout is not a double`() {
        val (isDouble, newLast) = decideDouble(lastTime = 1_000L, now = 2_300L, timeout = TIMEOUT)
        assertFalse(isDouble)
        assertEquals(2_300L, newLast)
    }

    @Test
    fun `bounce under the minimum gap is not a double`() {
        val (isDouble, _) = decideDouble(lastTime = 1_000L, now = 1_030L, timeout = TIMEOUT)
        assertFalse(isDouble)
    }

    @Test
    fun `a double consumes the pair so the next press starts fresh`() {
        val (isDouble, newLast) = decideDouble(lastTime = 1_000L, now = 1_500L, timeout = TIMEOUT)
        assertTrue(isDouble)
        assertEquals("tras un DOUBLE el par debe consumirse", 0L, newLast)
    }

    /**
     * La regresión que motiva el fix: un mando que repite a ~4 Hz mientras lo mantienes
     * pulsado. Antes, cada pulsación a partir de la segunda encadenaba otro DOUBLE (7 de 8).
     *
     * OJO: 4 no es "lo correcto", es lo mejor que puede hacer un detector de pares sin estado
     * — un par consumido cada dos pulsaciones. Distinguir de verdad "mantener pulsado" de
     * "pulsar rápido" necesita semántica de hold, que no existe aquí. Este test fija que el
     * encadenamiento está roto, no que los holds estén resueltos.
     */
    @Test
    fun `press and hold emits exactly one double per pair, not one per press`() {
        var last = 0L
        var doubles = 0
        // 8 pulsaciones cada 250 ms, todas dentro del timeout de 1200 ms.
        for (i in 1..8) {
            val now = i * 250L
            val (isDouble, newLast) = decideDouble(last, now, TIMEOUT)
            if (isDouble) doubles++
            last = newLast
        }
        assertEquals("un doble por par (4), no uno por pulsación (7)", 4, doubles)
    }
}
