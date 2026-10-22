package dev.kura.core.burst

import dev.kura.core.TestSupport
import dev.kura.core.config.Direction
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BurstSessionTest {
    private fun session(direction: Direction = Direction.OUT, step: Int = 1) =
        BurstSession("rafale-test", direction, step, TestSupport.factory())

    @Test
    fun `chaque lecture cree un delta signe avec le session_id`() {
        val s = session()
        val e = assertIs<BurstEvent.Created>(s.onScan("REF-0042", "Vis M6"))
        assertEquals(-1, e.op.value)
        assertEquals("rafale-test", e.op.sessionId)
        assertEquals(BurstLine("REF-0042", "Vis M6", -1, 1), e.line)
        s.direction = Direction.IN
        s.step = 10
        assertEquals(10, (s.onScan("REF-0043") as BurstEvent.Created).op.value)
    }

    @Test
    fun `lignes regroupees par article`() {
        val s = session()
        s.onScan("A"); s.onScan("B"); s.onScan("A"); s.onScan("A")
        assertEquals(listOf(BurstLine("A", null, -3, 3), BurstLine("B", null, -1, 1)), s.lines)
        assertEquals(4, s.operations.size)
    }

    @Test
    fun `annuler - retire si pas encore envoyee`() {
        val s = session()
        s.onScan("A")
        val second = (s.onScan("A") as BurstEvent.Created).op
        val undo = assertIs<UndoAction.RemoveQueued>(s.undo { true })
        assertEquals(second, undo.op)
        assertEquals(listOf(BurstLine("A", null, -1, 1)), s.lines)
    }

    @Test
    fun `annuler - operation inverse si deja envoyee`() {
        val s = session()
        val first = (s.onScan("A") as BurstEvent.Created).op
        val undo = assertIs<UndoAction.Compensate>(s.undo { false })
        assertEquals(1, undo.inverse.value)
        assertEquals(first.sessionId, undo.inverse.sessionId)
        assertEquals("A", undo.inverse.itemId)
        assertEquals(listOf(BurstLine("A", null, 0, 0)), s.lines)
        assertEquals(UndoAction.Nothing, s.undo { false })
        assertNull(s.lastUndoable)
    }

    @Test
    fun `annulation tardive - l'appli enregistre l'inverse elle-meme`() {
        val s = session()
        val op = (s.onScan("A") as BurstEvent.Created).op
        assertEquals(op, s.lastUndoable)
        val removed = assertIs<UndoAction.RemoveQueued>(s.undo { true })
        // Entre-temps l'opération est partie : l'appli crée l'inverse et l'enregistre dans la session.
        s.restoreAsSent(removed.op, removed.op.inverse(TestSupport.factory()))
        assertEquals(2, s.operations.size)
        assertEquals(BurstLine("A", null, 0, 0), s.lines.single())
    }

    @Test
    fun `corriger le total d'une ligne`() {
        val s = session()
        repeat(3) { s.onScan("A") }
        val fix = s.correctLine("A", -2)!!
        assertEquals(1, fix.value)
        assertEquals(-2, s.lines.single().total)
        assertNull(s.correctLine("A", -2))
        assertEquals(-5, s.correctLine("A", -7)!!.value)
    }

    @Test
    fun `identifiant de session lisible`() {
        val id = BurstSession.newSessionId(Instant.parse("2026-10-22T13:55:12Z"), ZoneId.of("Europe/Paris"), SecureRandom())
        assertTrue(Regex("^rafale-2026-10-22-155512-[0-9a-f]{4}$").matches(id), id)
    }
}
