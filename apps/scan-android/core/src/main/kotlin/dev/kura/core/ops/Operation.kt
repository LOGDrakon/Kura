package dev.kura.core.ops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
enum class OpType {
    @SerialName("delta") DELTA,
    @SerialName("set") SET;

    val wireName: String get() = if (this == DELTA) "delta" else "set"
}

/** Opération telle que définie par le contrat (`spec/api-contract.yaml`, schéma `Operation`). */
@Serializable
data class Operation(
    @SerialName("op_id") val opId: String,
    @SerialName("item_id") val itemId: String,
    val type: OpType,
    val value: Long,
    @SerialName("client_ts") val clientTs: String,
    @SerialName("device_id") val deviceId: String? = null,
    val user: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
) {
    /** Opération inverse (annulation d'un delta déjà envoyé). Seul un `delta` a un inverse. */
    fun inverse(factory: OperationFactory): Operation {
        require(type == OpType.DELTA) { "seul un delta peut être inversé" }
        return factory.delta(itemId, -value, sessionId)
    }

    /** Variables disponibles pour `request_mapping` et les chemins d'endpoint. */
    fun variables(): Map<String, JsonPrimitive> = buildMap {
        put("op_id", JsonPrimitive(opId))
        put("item_id", JsonPrimitive(itemId))
        put("type", JsonPrimitive(type.wireName))
        put("value", JsonPrimitive(value))
        put("client_ts", JsonPrimitive(clientTs))
        deviceId?.let { put("device_id", JsonPrimitive(it)) }
        user?.let { put("user", JsonPrimitive(it)) }
        sessionId?.let { put("session_id", JsonPrimitive(it)) }
    }
}

object OperationVariables {
    val NAMES = setOf("op_id", "item_id", "type", "value", "client_ts", "device_id", "user", "session_id")
}

/** Crée des opérations horodatées (UTC, ISO 8601) avec un `op_id` UUID v4. */
class OperationFactory(
    private val deviceId: () -> String?,
    private val user: () -> String?,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun delta(itemId: String, value: Long, sessionId: String? = null): Operation =
        create(itemId, OpType.DELTA, value, sessionId)

    fun set(itemId: String, value: Long, sessionId: String? = null): Operation =
        create(itemId, OpType.SET, value, sessionId)

    fun create(itemId: String, type: OpType, value: Long, sessionId: String? = null): Operation {
        val op = Operation(
            opId = newId(),
            itemId = itemId,
            type = type,
            value = value,
            clientTs = formatTimestamp(clock.instant()),
            deviceId = deviceId()?.takeIf { it.isNotBlank() },
            user = user()?.takeIf { it.isNotBlank() },
            sessionId = sessionId,
        )
        OperationValidator.validate(op)?.let { throw IllegalArgumentException(it) }
        return op
    }

    companion object {
        /** ISO 8601 UTC, à la milliseconde (ex. `2026-10-22T13:55:12.345Z`, ou `…:12Z` si 0 ms). */
        fun formatTimestamp(instant: Instant): String = instant.truncatedTo(ChronoUnit.MILLIS).toString()
    }
}
