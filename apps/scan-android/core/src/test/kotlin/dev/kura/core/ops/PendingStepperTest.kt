package dev.kura.core.ops

import dev.kura.core.TestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingStepperTest {
    private val f = TestSupport.factory()

    @Test
    fun `plusieurs appuis donnent un seul delta`() {
        val s = PendingStepper(250).step(1).step(1).step(10).step(-1)
        assertEquals(11, s.pendingDelta)
        assertEquals(261, s.projectedQuantity)
        val op = s.toOperation("REF-0042", f)!!
        assertEquals(OpType.DELTA, op.type)
        assertEquals(11, op.value)
    }

    @Test
    fun `appuis qui s'annulent - rien a envoyer`() {
        val s = PendingStepper(5).step(1).step(-1)
        assertFalse(s.hasChanges)
        assertNull(s.toOperation("R", f))
    }

    @Test
    fun `saisie de quantite - set, puis les pas ajustent la cible`() {
        val s = PendingStepper(250).step(3).setQuantity(12)
        assertTrue(s.isSetMode)
        assertEquals(0, s.pendingDelta)
        assertEquals(12, s.projectedQuantity)
        val s2 = s.step(-1).step(-20)
        assertEquals(0, s2.setTarget)
        val op = s.step(1).toOperation("R", f)!!
        assertEquals(OpType.SET, op.type)
        assertEquals(13, op.value)
    }

    @Test
    fun `set egal a la quantite connue - rien a envoyer, sauf si inconnue`() {
        assertNull(PendingStepper(12).setQuantity(12).toOperation("R", f))
        assertEquals(12, PendingStepper(null).setQuantity(12).toOperation("R", f)!!.value)
        assertNull(PendingStepper(null).step(2).projectedQuantity)
    }

    @Test
    fun `allow_set desactive`() {
        val s = PendingStepper(3, allowSet = false).setQuantity(9)
        assertFalse(s.isSetMode)
        assertEquals(PendingStepper(7, allowSet = false), s.step(2).reset(7))
    }
}
