package dev.kura.core.scan

import dev.kura.core.config.TriggerConfig
import dev.kura.core.config.TriggerMode
import dev.kura.core.scan.ScanTrigger.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanTriggerTest {
    private val cfg = TriggerConfig(timeoutMs = 6000)

    @Test
    fun `rien n'est decode avant l'appui`() {
        val t = ScanTrigger(cfg)
        assertEquals(State.Idle, t.state)
        assertFalse(t.shouldDecode(0))
        assertNull(t.onDecoded("REF-1", 10))
    }

    @Test
    fun `appui, lecture du premier code puis arret`() {
        val t = ScanTrigger(cfg)
        assertEquals(State.Armed(7000, ScanTrigger.Source.BUTTON), t.press(1000))
        assertTrue(t.shouldDecode(2000))
        assertEquals(4000, t.remainingMs(3000))
        assertEquals("REF-1", t.onDecoded("REF-1", 3000))
        assertEquals(State.Success("REF-1"), t.state)
        assertFalse(t.shouldDecode(3001))
        assertNull(t.onDecoded("REF-2", 3002)) // le code voisin n'est pas lu
        t.acknowledge()
        assertEquals(State.Idle, t.state)
    }

    @Test
    fun `delai depasse`() {
        val t = ScanTrigger(cfg)
        t.press(0)
        assertFalse(t.tick(5999))
        assertTrue(t.tick(6000))
        assertEquals(State.Timeout, t.state)
        assertFalse(t.tick(7000))
        assertNull(t.onDecoded("late", 6500))
        t.acknowledge()
        t.press(8000)
        assertTrue(t.state is State.Armed)
    }

    @Test
    fun `nouvel appui pendant la tentative annule`() {
        val t = ScanTrigger(cfg)
        t.press(0)
        assertEquals(State.Cancelled, t.press(100))
        assertFalse(t.shouldDecode(200))
        // Depuis Cancelled (non acquitté), un appui réarme.
        assertTrue(t.press(300) is State.Armed)
    }

    @Test
    fun `touches de volume`() {
        val t = ScanTrigger(cfg)
        assertTrue(t.volumeKey(0))
        assertEquals(State.Armed(6000, ScanTrigger.Source.VOLUME_KEY), t.state)
        assertTrue(t.volumeKey(10))
        assertEquals(State.Cancelled, t.state)

        val off = ScanTrigger(cfg.copy(volumeKeys = false))
        assertFalse(off.volumeKey(0))
        assertEquals(State.Idle, off.state)
    }

    @Test
    fun `mode continu toujours arme`() {
        val t = ScanTrigger(cfg.copy(mode = TriggerMode.CONTINUOUS))
        assertTrue(t.shouldDecode(0))
        assertEquals("A", t.onDecoded("A", 1))
        assertEquals("B", t.onDecoded("B", 2))
        assertTrue(t.shouldDecode(1_000_000))
        assertFalse(t.volumeKey(3))
        assertTrue(t.press(4) is State.Armed)
        assertNull(t.remainingMs(5))
    }

    @Test
    fun `filtre meme code`() {
        val f = SameCodeFilter(1500)
        assertTrue(f.accept("A", 0))
        assertFalse(f.accept("A", 1499))
        assertTrue(f.accept("B", 1500))
        assertTrue(f.accept("A", 1600))
        assertTrue(f.accept("A", 3100))
        f.reset()
        assertTrue(f.accept("A", 3101))
        val none = SameCodeFilter(0)
        assertTrue(none.accept("A", 0))
        assertTrue(none.accept("A", 0))
    }
}
