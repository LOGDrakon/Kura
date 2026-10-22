package dev.kura.scan.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.kura.scan.kura
import java.util.concurrent.TimeUnit

/**
 * Vide la file offline. Seule contrainte : le réseau (pas de service au premier plan, pas d'alarme exacte),
 * donc compatible avec l'économiseur de batterie.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.kura
        if (container.settings.config.value == null) return Result.success()
        val report = container.syncEngine.drain()
        report.quantities.forEach { (ref, q) -> container.itemCache.updateQuantity(ref, q) }
        val retry = report.retryInMs
        if (retry != null && !report.authFailed) container.syncScheduler.scheduleRetry(retry)
        return Result.success()
    }
}

class SyncScheduler(private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Envoi dès que le réseau est disponible. */
    fun requestNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build(),
        )
    }

    /** Nouvel essai après le délai de `sync.retry_backoff_s`. */
    fun scheduleRetry(delayMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            RETRY,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    private companion object {
        const val NOW = "kura-sync-now"
        const val RETRY = "kura-sync-retry"
    }
}
