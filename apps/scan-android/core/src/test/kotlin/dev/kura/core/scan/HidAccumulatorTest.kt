package dev.kura.core.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HidAccumulatorTest {
    private fun HidAccumulator.type(text: String, start: Long, gap: Long): Long {
        var t = start
        for (c in text) {
            onChar(c, t); t += gap
        }
        return t - gap
    }

    @Test
    fun `scanner materiel - frappes rapides terminees par Entree`() {
        val h = HidAccumulator()
        val last = h.type("REF-0042", 0, 8)
        assertEquals("REF-0042", h.onTerminator(last + 10))
        assertEquals(0, h.pending)
    }

    @Test
    fun `frappe humaine ignoree`() {
        val h = HidAccumulator()
        val last = h.type("REF-0042", 0, 180)
        assertNull(h.onTerminator(last + 150))
    }

    @Test
    fun `saisie lente puis rafale - seule la rafale compte`() {
        val h = HidAccumulator()
        h.type("abc", 0, 300)
        val last = h.type("3760123456789", 2000, 5)
        assertEquals("3760123456789", h.onTerminator(last + 5))
    }

    @Test
    fun `trop court, Entree tardive ou isolee`() {
        val h = HidAccumulator(minLength = 3)
        h.type("AB", 0, 5)
        assertNull(h.onTerminator(10))
        val last = h.type("ABCDEF", 100, 5)
        assertNull(h.onTerminator(last + 1000))
        assertNull(h.onTerminator(5000))
    }

    @Test
    fun `deux lectures consecutives`() {
        val h = HidAccumulator()
        var last = h.type("A-1", 0, 5)
        assertEquals("A-1", h.onTerminator(last + 5))
        last = h.type("B-2", last + 30, 5)
        assertEquals("B-2", h.onTerminator(last + 5))
    }
}
