package dev.kura.scan.data

import dev.kura.core.ops.OpType
import dev.kura.core.ops.Operation
import dev.kura.core.sync.OperationStore
import dev.kura.core.sync.QueueState
import dev.kura.core.sync.QueuedOperation
import dev.kura.core.template.ItemInfo
import dev.kura.scan.data.db.ItemCacheDao
import dev.kura.scan.data.db.ItemCacheEntity
import dev.kura.scan.data.db.QueueDao
import dev.kura.scan.data.db.QueuedOpEntity
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private val extrasSerializer = MapSerializer(String.serializer(), String.serializer())

/** File offline sur Room (implémente l'interface de `:core`). */
class RoomOperationStore(private val dao: QueueDao) : OperationStore {

    override suspend fun enqueue(op: Operation, extras: Map<String, String>) = enqueue(op, extras, null)

    suspend fun enqueue(op: Operation, extras: Map<String, String>, label: String?) {
        dao.insert(
            QueuedOpEntity(
                opId = op.opId,
                itemId = op.itemId,
                type = op.type.wireName,
                value = op.value,
                clientTs = op.clientTs,
                deviceId = op.deviceId,
                user = op.user,
                sessionId = op.sessionId,
                extrasJson = json.encodeToString(extrasSerializer, extras),
                label = label,
                state = QueueState.PENDING.name,
                attempts = 0,
                nextAttemptAt = 0,
                errorReason = null,
                lastMessage = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun due(nowMs: Long, limit: Int): List<QueuedOperation> = dao.due(nowMs, limit).map { it.toModel() }

    override suspend fun get(opId: String): QueuedOperation? = dao.get(opId)?.toModel()

    override suspend fun remove(opId: String) {
        dao.delete(opId)
    }

    /** Retourne `true` si l'opération était encore dans la file. */
    suspend fun removeIfPresent(opId: String): Boolean = dao.delete(opId) > 0

    override suspend fun markError(opId: String, reason: String, message: String?) = dao.markError(opId, reason, message)

    override suspend fun scheduleRetry(opId: String, attempts: Int, nextAttemptAtMs: Long, message: String?) =
        dao.scheduleRetry(opId, attempts, nextAttemptAtMs, message)

    override suspend fun requeue(opId: String, nowMs: Long) = dao.requeue(opId, nowMs)
}

fun QueuedOpEntity.toModel(): QueuedOperation = QueuedOperation(
    op = Operation(
        opId = opId,
        itemId = itemId,
        type = if (type == "set") OpType.SET else OpType.DELTA,
        value = value,
        clientTs = clientTs,
        deviceId = deviceId,
        user = user,
        sessionId = sessionId,
    ),
    extras = runCatching { json.decodeFromString(extrasSerializer, extrasJson) }.getOrDefault(emptyMap()),
    state = if (state == QueueState.ERROR.name) QueueState.ERROR else QueueState.PENDING,
    attempts = attempts,
    nextAttemptAtMs = nextAttemptAt,
    errorReason = errorReason,
    lastMessage = lastMessage,
)

/** Cache des fiches (dernière version connue de chaque article). */
class ItemCache(private val dao: ItemCacheDao) {
    suspend fun put(item: ItemInfo) {
        dao.put(ItemCacheEntity(item.ref, json.encodeToString(ItemInfo.serializer(), item), System.currentTimeMillis()))
        dao.trim(MAX_ITEMS)
    }

    suspend fun get(ref: String): ItemInfo? =
        dao.get(ref)?.let { runCatching { json.decodeFromString(ItemInfo.serializer(), it.json) }.getOrNull() }

    /** Met à jour la quantité connue après un envoi (`OperationResult.quantity`). */
    suspend fun updateQuantity(ref: String, quantity: Long) {
        val item = get(ref) ?: return
        put(item.copy(quantity = quantity, fields = item.fields + ("quantity" to quantity.toString())))
    }

    private companion object {
        const val MAX_ITEMS = 2000
    }
}
