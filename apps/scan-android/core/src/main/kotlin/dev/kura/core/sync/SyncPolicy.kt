package dev.kura.core.sync

/** Type d'échec de transport ou de protocole (hors statut métier renvoyé par le serveur). */
enum class FailureKind {
    /** Pas de réseau, hôte injoignable, délai dépassé. */
    NETWORK,

    /** 5xx, 408, 429 : le serveur est momentanément indisponible. */
    SERVER,

    /** 401 / 403 : authentification absente ou refusée. Inutile d'insister sur les autres opérations. */
    AUTH,

    /** Autre 4xx : la requête est refusée telle quelle. */
    CLIENT,

    /** Réponse 2xx illisible. */
    PROTOCOL,
}

data class Failure(val kind: FailureKind, val httpStatus: Int?, val message: String)

/** Avis discret à montrer à l'utilisateur. */
enum class Notice {
    /** « Correction plus récente déjà enregistrée » (statut `superseded`). */
    SUPERSEDED,
}

sealed interface QueueAction {
    /** Retirée de la file. */
    data class Remove(val notice: Notice? = null) : QueueAction

    /** Gardée en erreur : l'utilisateur corrige, relance ou supprime. */
    data class KeepInError(val reason: String, val message: String?) : QueueAction

    /** Gardée, nouvel essai selon `sync.retry_backoff_s`. [stopDrain] : inutile d'envoyer les suivantes maintenant. */
    data class Retry(val message: String, val stopDrain: Boolean) : QueueAction
}

/** Correspondance réponse serveur → action sur la file (architecture §4.5). */
object SyncPolicy {
    fun actionFor(result: OperationResult): QueueAction = when (result.status) {
        ResultStatus.APPLIED, ResultStatus.DUPLICATE -> QueueAction.Remove()
        ResultStatus.SUPERSEDED -> QueueAction.Remove(Notice.SUPERSEDED)
        ResultStatus.REJECTED -> QueueAction.KeepInError(result.reason ?: "invalid", result.message)
    }

    fun actionFor(failure: Failure): QueueAction = when (failure.kind) {
        FailureKind.NETWORK, FailureKind.SERVER -> QueueAction.Retry(failure.message, stopDrain = true)
        FailureKind.AUTH -> QueueAction.Retry(failure.message, stopDrain = true)
        FailureKind.CLIENT -> QueueAction.KeepInError(clientReason(failure.httpStatus), failure.message)
        FailureKind.PROTOCOL -> QueueAction.KeepInError("server_error", failure.message)
    }

    private fun clientReason(status: Int?): String = when (status) {
        403 -> "forbidden"
        404 -> "unknown_item"
        409 -> "conflict"
        else -> "invalid"
    }

    fun classifyHttpStatus(status: Int): FailureKind? = when {
        status in 200..299 -> null
        status == 401 || status == 403 -> FailureKind.AUTH
        status == 408 || status == 429 || status >= 500 -> FailureKind.SERVER
        else -> FailureKind.CLIENT
    }

    /** Découpe en lots de `sync.max_batch_size`. */
    fun <T> batches(items: List<T>, maxBatchSize: Int): List<List<T>> = items.chunked(maxBatchSize.coerceIn(1, 500))
}

/** Délais entre essais : `retry_backoff_s[n-1]` pour le n-ième échec, puis le dernier délai indéfiniment. */
class Backoff(scheduleSeconds: List<Int>) {
    private val schedule = scheduleSeconds.filter { it > 0 }.ifEmpty { listOf(5, 30, 120) }

    fun delayMs(attempt: Int): Long {
        val index = (attempt - 1).coerceIn(0, schedule.size - 1)
        return schedule[index] * 1000L
    }
}
