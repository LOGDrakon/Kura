package dev.kura.core.sync

import dev.kura.core.net.OutgoingOperation
import dev.kura.core.ops.Operation

enum class QueueState { PENDING, ERROR }

data class QueuedOperation(
    val op: Operation,
    val extras: Map<String, String> = emptyMap(),
    val state: QueueState = QueueState.PENDING,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long = 0,
    val errorReason: String? = null,
    val lastMessage: String? = null,
) {
    val outgoing: OutgoingOperation get() = OutgoingOperation(op, extras)
}

/** File offline. Implémentée par Room dans l'appli, en mémoire dans les tests. */
interface OperationStore {
    suspend fun enqueue(op: Operation, extras: Map<String, String> = emptyMap())

    /** Opérations PENDING dont l'échéance est passée, dans l'ordre de création. */
    suspend fun due(nowMs: Long, limit: Int): List<QueuedOperation>

    suspend fun get(opId: String): QueuedOperation?

    suspend fun remove(opId: String)

    suspend fun markError(opId: String, reason: String, message: String?)

    suspend fun scheduleRetry(opId: String, attempts: Int, nextAttemptAtMs: Long, message: String?)

    /** Remet une opération en erreur dans la file (relance manuelle). */
    suspend fun requeue(opId: String, nowMs: Long)
}

/** Implémentation en mémoire (tests, et repli si la base est indisponible). */
class InMemoryOperationStore : OperationStore {
    private val items = LinkedHashMap<String, QueuedOperation>()

    val all: List<QueuedOperation> get() = synchronized(items) { items.values.toList() }

    override suspend fun enqueue(op: Operation, extras: Map<String, String>) {
        synchronized(items) { items.putIfAbsent(op.opId, QueuedOperation(op, extras)) }
    }

    override suspend fun due(nowMs: Long, limit: Int): List<QueuedOperation> = synchronized(items) {
        items.values.filter { it.state == QueueState.PENDING && it.nextAttemptAtMs <= nowMs }.take(limit)
    }

    override suspend fun get(opId: String): QueuedOperation? = synchronized(items) { items[opId] }

    override suspend fun remove(opId: String) {
        synchronized(items) { items.remove(opId) }
    }

    override suspend fun markError(opId: String, reason: String, message: String?) = update(opId) {
        it.copy(state = QueueState.ERROR, errorReason = reason, lastMessage = message)
    }

    override suspend fun scheduleRetry(opId: String, attempts: Int, nextAttemptAtMs: Long, message: String?) = update(opId) {
        it.copy(attempts = attempts, nextAttemptAtMs = nextAttemptAtMs, lastMessage = message)
    }

    override suspend fun requeue(opId: String, nowMs: Long) = update(opId) {
        it.copy(state = QueueState.PENDING, nextAttemptAtMs = nowMs, errorReason = null, lastMessage = null)
    }

    private fun update(opId: String, f: (QueuedOperation) -> QueuedOperation) {
        synchronized(items) { items[opId]?.let { items[opId] = f(it) } }
    }
}
