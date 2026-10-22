package dev.kura.core.ops

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Validation identique à celle du serveur de référence (`tools/mock-server/lib/store.js`, fonction `validate`),
 * avec les mêmes messages : une opération refusée ici le serait aussi par le serveur.
 */
object OperationValidator {
    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    /** Retourne le motif du refus, ou `null` si l'opération est valide. */
    fun validate(op: Operation): String? {
        if (!UUID_RE.matches(op.opId)) return "op_id doit être un UUID"
        if (op.itemId.isEmpty() || op.itemId.length > 128) return "item_id invalide"
        if (op.type == OpType.DELTA && op.value == 0L) return "un delta ne peut pas être nul"
        if (op.type == OpType.SET && op.value < 0) return "un set ne peut pas être négatif"
        if (!isIsoTimestamp(op.clientTs)) return "client_ts doit être une date ISO 8601"
        return null
    }

    fun isIsoTimestamp(s: String): Boolean = try {
        OffsetDateTime.parse(s)
        true
    } catch (e: DateTimeParseException) {
        false
    }
}
