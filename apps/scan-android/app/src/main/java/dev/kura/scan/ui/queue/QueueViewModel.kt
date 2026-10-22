package dev.kura.scan.ui.queue

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kura.core.config.SyncMode
import dev.kura.scan.data.db.QueuedOpEntity
import dev.kura.scan.kura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QueueViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.kura

    val items: StateFlow<List<QueuedOpEntity>> =
        container.queueDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val count: StateFlow<Int> =
        container.queueDao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _authFailed = MutableStateFlow(false)
    val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

    val manualMode: Boolean get() = container.settings.config.value?.sync?.mode == SyncMode.MANUAL

    /** Adresse HTTP à confirmer avant l'envoi, sinon `null`. */
    fun cleartextToConfirm(): String? = container.cleartextToConfirm()

    fun retry(opId: String) {
        viewModelScope.launch {
            container.queueDao.requeue(opId, System.currentTimeMillis())
            sendNow()
        }
    }

    fun delete(opId: String) {
        viewModelScope.launch { container.queueDao.delete(opId) }
    }

    /** Envoi immédiat de tout ce qui est en attente (ignore le délai de nouvel essai). */
    fun sendNow() {
        if (container.settings.config.value == null || container.cleartextToConfirm() != null || _sending.value) return
        _sending.value = true
        viewModelScope.launch {
            try {
                container.queueDao.makeAllDue(System.currentTimeMillis())
                val report = withContext(Dispatchers.IO) { container.syncEngine.drain() }
                report.quantities.forEach { (ref, q) -> container.itemCache.updateQuantity(ref, q) }
                _authFailed.value = report.authFailed
                report.retryInMs?.let { if (!report.authFailed) container.syncScheduler.scheduleRetry(it) }
            } finally {
                _sending.value = false
            }
        }
    }
}
