package dev.kura.core.ops

import dev.kura.core.TestSupport
import dev.kura.core.net.KuraRequests
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationTest {
    private val valid = Operation("4b2f8d4e-0a7c-4a53-9d55-2f1e9b0c6a11", "REF-0042", OpType.DELTA, -1, "2026-10-22T13:55:12Z")

    @Test
    fun `serialisation conforme au contrat`() {
        val op = valid.copy(deviceId = "tablette-atelier-2", user = "jdupont", sessionId = "rafale-2026-10-22-1355")
        val json = KuraRequests.JSON.encodeToString(Operation.serializer(), op)
        assertEquals(
            Json.parseToJsonElement(
                """{"op_id":"4b2f8d4e-0a7c-4a53-9d55-2f1e9b0c6a11","item_id":"REF-0042","type":"delta","value":-1,
                   "client_ts":"2026-10-22T13:55:12Z","device_id":"tablette-atelier-2","user":"jdupont","session_id":"rafale-2026-10-22-1355"}""",
            ),
            Json.parseToJsonElement(json),
        )
        // Champs optionnels absents : omis (pas de null).
        assertEquals(
            """{"op_id":"4b2f8d4e-0a7c-4a53-9d55-2f1e9b0c6a11","item_id":"REF-0042","type":"delta","value":-1,"client_ts":"2026-10-22T13:55:12Z"}""",
            KuraRequests.JSON.encodeToString(Operation.serializer(), valid),
        )
    }

    @Test
    fun `validation identique au serveur de reference`() {
        assertNull(OperationValidator.validate(valid))
        assertEquals("op_id doit être un UUID", OperationValidator.validate(valid.copy(opId = "abc")))
        assertEquals("item_id invalide", OperationValidator.validate(valid.copy(itemId = "")))
        assertEquals("item_id invalide", OperationValidator.validate(valid.copy(itemId = "x".repeat(129))))
        assertEquals("un delta ne peut pas être nul", OperationValidator.validate(valid.copy(value = 0)))
        assertEquals("un set ne peut pas être négatif", OperationValidator.validate(valid.copy(type = OpType.SET, value = -1)))
        assertNull(OperationValidator.validate(valid.copy(type = OpType.SET, value = 0)))
        assertEquals("client_ts doit être une date ISO 8601", OperationValidator.validate(valid.copy(clientTs = "hier")))
        assertNull(OperationValidator.validate(valid.copy(clientTs = "2026-10-22T15:55:12.123+02:00")))
        assertNull(OperationValidator.validate(valid.copy(opId = valid.opId.uppercase())))
    }

    @Test
    fun `fabrique - UUID v4, horodatage UTC, identite`() {
        val f = OperationFactory({ "dev-1" }, { " " }, TestSupport.fixedClock("2026-10-22T13:55:12.345678Z"))
        val op = f.delta("REF-1", 5, "s1")
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(op.opId))
        assertEquals("2026-10-22T13:55:12.345Z", op.clientTs)
        assertEquals("dev-1", op.deviceId)
        assertNull(op.user) // vide → omis
        assertEquals("s1", op.sessionId)
        assertEquals("2026-10-22T13:55:12Z", OperationFactory.formatTimestamp(Instant.parse("2026-10-22T13:55:12Z")))
        assertFailsWith<IllegalArgumentException> { f.delta("REF-1", 0) }
        assertFailsWith<IllegalArgumentException> { f.set("REF-1", -2) }
    }

    @Test
    fun `operation inverse`() {
        val f = TestSupport.factory()
        val op = f.delta("REF-1", -3, "s")
        val inv = op.inverse(f)
        assertEquals(3, inv.value)
        assertEquals("s", inv.sessionId)
        assertFailsWith<IllegalArgumentException> { f.set("REF-1", 3).inverse(f) }
    }

    @Test
    fun `variables de gabarit`() {
        val v = valid.copy(user = "jd").variables()
        assertEquals(setOf("op_id", "item_id", "type", "value", "client_ts", "user"), v.keys)
        assertEquals("delta", v["type"]!!.content)
        assertTrue(v.keys.all { it in OperationVariables.NAMES })
    }
}
