package dev.kura.core.net

import dev.kura.core.sync.BatchResponse
import dev.kura.core.sync.Failure
import dev.kura.core.sync.FailureKind
import dev.kura.core.sync.OperationResult
import dev.kura.core.sync.ResultStatus
import dev.kura.core.sync.SyncPolicy
import dev.kura.core.template.ItemInfo
import dev.kura.core.template.ItemMapper
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

sealed interface ItemLookup {
    data class Found(val item: ItemInfo) : ItemLookup
    data object NotFound : ItemLookup
    data class Failed(val failure: Failure) : ItemLookup
}

sealed interface SendOutcome {
    data class Result(val result: OperationResult) : SendOutcome
    data class Failed(val failure: Failure) : SendOutcome
}

/** Résultat d'un envoi par lot : un résultat par `op_id` envoyé. */
data class BatchOutcome(val outcomes: Map<String, SendOutcome>)

/** Client du serveur de l'entreprise : contrat Kura ou mapping de la config. */
class KuraClient(
    val requests: KuraRequests,
    private val transport: HttpTransport,
) {
    private val json = KuraRequests.JSON
    private val config = requests.config

    suspend fun getItem(fields: Map<String, String>, ref: String): ItemLookup {
        val response = when (val r = call(requests.getItem(fields))) {
            is Either.Left -> return ItemLookup.Failed(r.value)
            is Either.Right -> r.value
        }
        if (response.status == 404) return ItemLookup.NotFound
        SyncPolicy.classifyHttpStatus(response.status)?.let {
            return ItemLookup.Failed(Failure(it, response.status, errorMessage(response)))
        }
        val body = parse(response.body) ?: return ItemLookup.Failed(
            Failure(FailureKind.PROTOCOL, response.status, "Réponse du serveur illisible (JSON attendu)."),
        )
        return ItemLookup.Found(ItemMapper.fromResponse(ref, body, config.responseMapping))
    }

    suspend fun send(out: OutgoingOperation): SendOutcome {
        val response = when (val r = call(requests.sendOperation(out))) {
            is Either.Left -> return SendOutcome.Failed(r.value)
            is Either.Right -> r.value
        }
        SyncPolicy.classifyHttpStatus(response.status)?.let {
            return SendOutcome.Failed(Failure(it, response.status, errorMessage(response)))
        }
        val result = parse(response.body)?.let { decodeResult(it) }
        return when {
            result != null -> SendOutcome.Result(result.copy(opId = result.opId ?: out.op.opId))
            // Serveur hors contrat (request_mapping) : un 2xx vaut acceptation.
            !config.usesKuraContract -> SendOutcome.Result(OperationResult(out.op.opId, ResultStatus.APPLIED))
            else -> SendOutcome.Failed(
                Failure(FailureKind.PROTOCOL, response.status, "Réponse du serveur illisible (OperationResult attendu)."),
            )
        }
    }

    suspend fun sendBatch(ops: List<OutgoingOperation>): BatchOutcome {
        val ids = ops.map { it.op.opId }
        val response = when (val r = call(requests.sendBatch(ops))) {
            is Either.Left -> return BatchOutcome(ids.associateWith { SendOutcome.Failed(r.value) })
            is Either.Right -> r.value
        }
        SyncPolicy.classifyHttpStatus(response.status)?.let { kind ->
            val f = Failure(kind, response.status, errorMessage(response))
            return BatchOutcome(ids.associateWith { SendOutcome.Failed(f) })
        }
        val parsed = parse(response.body)?.let {
            try {
                json.decodeFromJsonElement(BatchResponse.serializer(), it)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (parsed == null) {
            if (!config.usesKuraContract) {
                return BatchOutcome(ids.associateWith { SendOutcome.Result(OperationResult(it, ResultStatus.APPLIED)) })
            }
            val f = Failure(FailureKind.PROTOCOL, response.status, "Réponse du lot illisible (results attendu).")
            return BatchOutcome(ids.associateWith { SendOutcome.Failed(f) })
        }
        // Résultats associés par op_id, sinon par position (le contrat garantit le même ordre).
        val byId = parsed.results.filter { it.opId != null }.associateBy { it.opId!! }
        val outcomes = LinkedHashMap<String, SendOutcome>()
        ids.forEachIndexed { i, id ->
            val result = byId[id] ?: parsed.results.getOrNull(i)?.takeIf { it.opId == null }?.copy(opId = id)
            outcomes[id] = if (result != null) SendOutcome.Result(result)
            else SendOutcome.Failed(Failure(FailureKind.SERVER, response.status, "Pas de résultat pour cette opération dans le lot."))
        }
        return BatchOutcome(outcomes)
    }

    /** Récupère la config distante : texte de la config et signature (base64), ou `null` si absente. */
    suspend fun getRemoteConfig(): Pair<String, String?>? {
        val response = (call(requests.getConfig()) as? Either.Right)?.value ?: return null
        if (!response.isSuccessful) return null
        val obj = parse(response.body) as? JsonObject ?: return null
        val cfg = (obj["config"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val sig = (obj["signature"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return cfg to sig
    }

    private fun decodeResult(el: JsonElement): OperationResult? = try {
        json.decodeFromJsonElement(OperationResult.serializer(), el)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun call(spec: HttpRequestSpec): Either<Failure, HttpResponseData> = try {
        Either.Right(transport.execute(spec))
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Either.Left(Failure(FailureKind.NETWORK, null, "Serveur injoignable : ${e.message ?: e.javaClass.simpleName}"))
    }

    private fun parse(body: String): JsonElement? = try {
        if (body.isBlank()) null else json.parseToJsonElement(body)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun errorMessage(response: HttpResponseData): String {
        val obj = parse(response.body) as? JsonObject
        val msg = (obj?.get("message") as? JsonPrimitive)?.content ?: (obj?.get("error") as? JsonPrimitive)?.content
        return when (response.status) {
            401 -> "Authentification refusée (401)" + (msg?.let { " : $it" } ?: ".")
            403 -> "Accès refusé (403)" + (msg?.let { " : $it" } ?: ".")
            else -> "Erreur HTTP ${response.status}" + (msg?.let { " : $it" } ?: ".")
        }
    }

    private sealed interface Either<out L, out R> {
        data class Left<L>(val value: L) : Either<L, Nothing>
        data class Right<R>(val value: R) : Either<Nothing, R>
    }
}
