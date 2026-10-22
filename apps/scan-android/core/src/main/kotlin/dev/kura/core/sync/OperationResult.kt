package dev.kura.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ResultStatus {
    @SerialName("applied") APPLIED,
    @SerialName("duplicate") DUPLICATE,
    @SerialName("superseded") SUPERSEDED,
    @SerialName("rejected") REJECTED,
}

/** Schéma `OperationResult` du contrat. [reason] reste en texte pour tolérer des motifs inconnus. */
@Serializable
data class OperationResult(
    @SerialName("op_id") val opId: String? = null,
    val status: ResultStatus,
    val reason: String? = null,
    val message: String? = null,
    val quantity: Long? = null,
    @SerialName("server_received_at") val serverReceivedAt: String? = null,
)

@Serializable
data class BatchResponse(val results: List<OperationResult>)
