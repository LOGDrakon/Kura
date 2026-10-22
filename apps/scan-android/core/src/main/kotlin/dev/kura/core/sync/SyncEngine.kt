package dev.kura.core.sync

import dev.kura.core.config.KuraConfig
import dev.kura.core.net.KuraClient
import dev.kura.core.net.SendOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ce qui est arrivé à une opération lors d'un vidage de la file. */
sealed interface OpOutcome {
    data class Sent(val result: OperationResult, val notice: Notice?) : OpOutcome
    data class InError(val reason: String, val message: String?) : OpOutcome
    data class WillRetry(val message: String, val retryInMs: Long) : OpOutcome
}

data class DrainReport(
    val outcomes: Map<String, OpOutcome> = emptyMap(),
    /** Dernière quantité connue par article (pour mettre à jour le cache des fiches). */
    val quantities: Map<String, Long> = emptyMap(),
    /** Authentification refusée : inutile de réessayer sans action de l'utilisateur. */
    val authFailed: Boolean = false,
    /** Délai avant le prochain essai, si des opérations restent à renvoyer. */
    val retryInMs: Long? = null,
) {
    val sentCount: Int get() = outcomes.values.count { it is OpOutcome.Sent }
}

/**
 * Vide la file offline : envoi unitaire ou par lot (`sync.batch`, `max_batch_size`),
 * puis applique [SyncPolicy] à chaque réponse. Sûr à appeler depuis plusieurs endroits (mutex).
 */
class SyncEngine(
    private val store: OperationStore,
    private val client: () -> KuraClient,
    private val config: () -> KuraConfig,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun drain(): DrainReport = mutex.withLock { drainLocked() }

    private suspend fun drainLocked(): DrainReport {
        val cfg = config()
        val api = client()
        val backoff = Backoff(cfg.sync.retryBackoffS)
        val batchSize = if (cfg.usesBatch) cfg.sync.maxBatchSize.coerceIn(1, 500) else 1
        val outcomes = LinkedHashMap<String, OpOutcome>()
        val quantities = LinkedHashMap<String, Long>()
        var authFailed = false
        var retryInMs: Long? = null

        var rounds = 0
        loop@ while (rounds++ < MAX_ROUNDS) {
            val due = store.due(nowMs(), batchSize)
            if (due.isEmpty()) break
            val results: Map<String, SendOutcome> =
                if (cfg.usesBatch) api.sendBatch(due.map { it.outgoing }).outcomes
                else mapOf(due[0].op.opId to api.send(due[0].outgoing))

            var stop = false
            for (queued in due) {
                val id = queued.op.opId
                val outcome = results[id] ?: continue
                val action = when (outcome) {
                    is SendOutcome.Result -> SyncPolicy.actionFor(outcome.result)
                    is SendOutcome.Failed -> {
                        if (outcome.failure.kind == dev.kura.core.sync.FailureKind.AUTH) authFailed = true
                        SyncPolicy.actionFor(outcome.failure)
                    }
                }
                when (action) {
                    is QueueAction.Remove -> {
                        store.remove(id)
                        val result = (outcome as SendOutcome.Result).result
                        result.quantity?.let { quantities[queued.op.itemId] = it }
                        outcomes[id] = OpOutcome.Sent(result, action.notice)
                    }
                    is QueueAction.KeepInError -> {
                        store.markError(id, action.reason, action.message)
                        outcomes[id] = OpOutcome.InError(action.reason, action.message)
                    }
                    is QueueAction.Retry -> {
                        val attempts = queued.attempts + 1
                        val delay = backoff.delayMs(attempts)
                        store.scheduleRetry(id, attempts, nowMs() + delay, action.message)
                        outcomes[id] = OpOutcome.WillRetry(action.message, delay)
                        retryInMs = minOf(retryInMs ?: delay, delay)
                        if (action.stopDrain) stop = true
                    }
                }
            }
            if (stop) break@loop
        }
        return DrainReport(outcomes, quantities, authFailed, retryInMs)
    }

    private companion object {
        const val MAX_ROUNDS = 1000
    }
}
