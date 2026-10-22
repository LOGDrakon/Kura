package dev.kura.core.burst

import dev.kura.core.config.Direction
import dev.kura.core.ops.OpType
import dev.kura.core.ops.Operation
import dev.kura.core.ops.OperationFactory
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ligne du panneau de session : un article, total signé de ses mouvements. */
data class BurstLine(
    val itemId: String,
    val label: String?,
    val total: Long,
    val scans: Int,
)

sealed interface BurstEvent {
    data class Created(val op: Operation, val line: BurstLine) : BurstEvent
    data class Ignored(val reason: String) : BurstEvent
}

sealed interface UndoAction {
    /** La dernière opération n'est pas encore partie : la retirer de la file. */
    data class RemoveQueued(val op: Operation) : UndoAction

    /** Déjà envoyée : mettre en file l'opération inverse. */
    data class Compensate(val inverse: Operation) : UndoAction

    data object Nothing : UndoAction
}

/**
 * Session du mode rafale (ADR 0004) : chaque lecture crée immédiatement un delta `±step`,
 * toutes les opérations portent le même `session_id`.
 * Le filtre « même code relu » est appliqué en amont (mode continu et HID seulement, ADR 0006).
 */
class BurstSession(
    val sessionId: String,
    var direction: Direction,
    var step: Int,
    private val factory: OperationFactory,
) {
    private val ops = mutableListOf<Operation>()
    private val undoStack = ArrayDeque<Operation>()
    private val labels = LinkedHashMap<String, String>()

    val operations: List<Operation> get() = ops.toList()

    /** Lignes regroupées par article, dans l'ordre de la première lecture. */
    val lines: List<BurstLine>
        get() = ops.groupBy { it.itemId }.map { (item, list) ->
            BurstLine(item, labels[item], list.sumOf { it.value }, list.count { it.opId in scanIds })
        }

    private val scanIds = HashSet<String>()

    fun onScan(itemId: String, label: String? = null): BurstEvent {
        if (step < 1) return BurstEvent.Ignored("pas invalide")
        label?.let { labels[itemId] = it }
        val op = factory.delta(itemId, step.toLong() * direction.sign, sessionId)
        ops += op
        scanIds += op.opId
        undoStack.addLast(op)
        return BurstEvent.Created(op, lineFor(itemId))
    }

    /** Prochaine opération qu'annulerait [undo], s'il y en a une. */
    val lastUndoable: Operation? get() = undoStack.lastOrNull()

    /**
     * Cas limite de [UndoAction.RemoveQueued] : l'opération est partie entre-temps (la suppression dans la file
     * n'a rien trouvé). L'appli met alors [inverse] en file et le signale ici : la session garde l'original et l'inverse.
     */
    fun restoreAsSent(original: Operation, inverse: Operation) {
        require(inverse.type == OpType.DELTA && inverse.value == -original.value) { "inverse incohérent" }
        ops += original
        ops += inverse
    }

    fun setLabel(itemId: String, label: String) {
        labels[itemId] = label
    }

    /**
     * Annule la dernière lecture. [isQueued] indique si l'opération est encore dans la file (non envoyée).
     */
    fun undo(isQueued: (String) -> Boolean): UndoAction {
        val last = undoStack.removeLastOrNull() ?: return UndoAction.Nothing
        return if (isQueued(last.opId)) {
            ops.removeAll { it.opId == last.opId }
            scanIds -= last.opId
            UndoAction.RemoveQueued(last)
        } else {
            val inverse = last.inverse(factory)
            ops += inverse
            scanIds -= last.opId
            UndoAction.Compensate(inverse)
        }
    }

    /**
     * Corrige le total d'une ligne (valeur signée, ex. −2 pour « 2 sorties »).
     * Retourne le delta de correction à mettre en file, ou `null` si rien ne change.
     */
    fun correctLine(itemId: String, targetTotal: Long): Operation? {
        val current = ops.filter { it.itemId == itemId }.sumOf { it.value }
        val diff = targetTotal - current
        if (diff == 0L) return null
        val op = factory.create(itemId, OpType.DELTA, diff, sessionId)
        ops += op
        return op
    }

    private fun lineFor(itemId: String): BurstLine = lines.first { it.itemId == itemId }

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

        /** Ex. `rafale-2026-10-22-135512-3f9a` (heure locale de l'appareil + suffixe aléatoire). */
        fun newSessionId(now: Instant, zone: ZoneId, random: SecureRandom = SecureRandom()): String {
            val suffix = "%04x".format(random.nextInt(0x10000))
            return "rafale-" + FORMAT.format(now.atZone(zone)) + "-" + suffix
        }
    }
}
