package dev.kura.scan.ui.scan

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kura.core.burst.BurstEvent
import dev.kura.core.burst.BurstLine
import dev.kura.core.burst.BurstSession
import dev.kura.core.burst.UndoAction
import dev.kura.core.config.CodeFormat
import dev.kura.core.config.ConfigQr
import dev.kura.core.config.Direction
import dev.kura.core.config.KuraConfig
import dev.kura.core.config.SyncMode
import dev.kura.core.config.TriggerConfig
import dev.kura.core.net.ItemLookup
import dev.kura.core.ops.Operation
import dev.kura.core.ops.PendingStepper
import dev.kura.core.scan.HidAccumulator
import dev.kura.core.scan.ParseOutcome
import dev.kura.core.scan.SameCodeFilter
import dev.kura.core.scan.ScanParser
import dev.kura.core.scan.ScanTrigger
import dev.kura.core.scan.ScannedCode
import dev.kura.core.sync.FailureKind
import dev.kura.core.sync.Notice
import dev.kura.core.sync.OpOutcome
import dev.kura.core.template.DisplayField
import dev.kura.core.template.ItemInfo
import dev.kura.core.template.ItemMapper
import dev.kura.scan.R
import dev.kura.scan.kura
import dev.kura.scan.scan.Feedback
import dev.kura.scan.ui.common.Banner
import dev.kura.scan.ui.common.UiText
import dev.kura.scan.ui.common.signed
import dev.kura.scan.ui.common.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

enum class ScanMode { ITEM, BURST }

sealed interface TriggerUi {
    data object Idle : TriggerUi
    data class Armed(val remaining: Float) : TriggerUi
    data object Continuous : TriggerUi
    data object Success : TriggerUi
    data object Failed : TriggerUi
}

data class ItemPaneState(
    val code: ScannedCode,
    val loading: Boolean = true,
    val item: ItemInfo? = null,
    val fields: List<DisplayField> = emptyList(),
    val imageUrl: String? = null,
    val imageHeaders: Map<String, String> = emptyMap(),
    val offline: Boolean = false,
    val fromCache: Boolean = false,
    val notFound: Boolean = false,
    val stepper: PendingStepper,
    val stepButtons: List<Int> = listOf(1),
    val submitting: Boolean = false,
    val result: Banner? = null,
) {
    val title: String get() = item?.name ?: code.itemId
}

data class ScanUiState(
    val config: KuraConfig? = null,
    val mode: ScanMode = ScanMode.ITEM,
    val direction: Direction = Direction.OUT,
    val trigger: TriggerUi = TriggerUi.Idle,
    val banner: Banner? = null,
    val item: ItemPaneState? = null,
    val burstLines: List<BurstLine> = emptyList(),
    val burstStep: Int = 1,
    val canUndo: Boolean = false,
    val torchOn: Boolean = false,
    val hasTorch: Boolean = false,
    val cameraAvailable: Boolean = true,
    /** Adresse HTTP à faire confirmer (avertissement « connexion non chiffrée »). */
    val cleartextPrompt: String? = null,
)

/**
 * Écran Scan : déclenchement (ADR 0006), mode Fiche (lecture + validation) et mode Rafale (ADR 0004),
 * saisie HID et touches de volume. La logique métier est dans `:core` ; ici, l'orchestration.
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.kura
    private val feedback = Feedback(app)

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _configQr = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** QR de configuration lus : l'écran les transmet à l'import. */
    val configQr: SharedFlow<String> = _configQr.asSharedFlow()

    private val lock = Any()
    private var trigger = ScanTrigger(TriggerConfig())
    private var sameCode = SameCodeFilter(1500)
    private val hid = HidAccumulator()
    private var session: BurstSession? = null
    private var ticker: Job? = null
    private var resetJob: Job? = null

    /** Vrai quand l'écran Scan est affiché (touches de volume et HID routées ici). */
    @Volatile
    var screenActive: Boolean = false

    init {
        viewModelScope.launch {
            container.settings.config.collect { applyConfig(it) }
        }
    }

    private fun applyConfig(config: KuraConfig?) {
        val trig = config?.scan?.trigger ?: TriggerConfig()
        synchronized(lock) {
            trigger = ScanTrigger(trig)
            sameCode = SameCodeFilter((config?.burst?.sameCodeCooldownMs ?: 1500).toLong())
        }
        val direction = config?.burst?.defaultDirection ?: Direction.OUT
        session = config?.let { newSession(it, direction) }
        _state.update {
            it.copy(
                config = config,
                direction = direction,
                mode = if (config?.burst?.enabled == false) ScanMode.ITEM else it.mode,
                trigger = if (trigger.isContinuous) TriggerUi.Continuous else TriggerUi.Idle,
                item = null,
                burstLines = emptyList(),
                burstStep = config?.burst?.defaultStep ?: 1,
                canUndo = false,
            )
        }
    }

    private fun newSession(config: KuraConfig, direction: Direction) = BurstSession(
        BurstSession.newSessionId(Instant.now(), ZoneId.systemDefault()),
        direction,
        config.burst.defaultStep,
        container.operationFactory,
    )

    // --- Déclenchement ----------------------------------------------------------------------

    /** Appelé par l'analyseur (fil d'arrière-plan) avant chaque image. */
    fun isArmedForAnalyzer(): Boolean = synchronized(lock) { trigger.shouldDecode(SystemClock.elapsedRealtime()) }

    /** Code décodé par ZXing (fil d'arrière-plan). */
    fun onFrameDecoded(text: String) {
        val now = SystemClock.elapsedRealtime()
        val accepted = synchronized(lock) {
            val code = trigger.onDecoded(text, now) ?: return
            if (trigger.isContinuous && !sameCode.accept(code, now)) return
            code
        }
        viewModelScope.launch { handleRaw(accepted, fromCamera = true) }
    }

    fun onScanButton() = arm { trigger.press(it) }

    /** Touche de volume : retourne `true` si elle a été utilisée (et ne doit pas changer le volume). */
    fun onVolumeKey(): Boolean {
        if (!screenActive || _state.value.item != null) return false
        var used = false
        arm { now -> used = trigger.volumeKey(now) }
        return used
    }

    private fun arm(action: (Long) -> Unit) {
        synchronized(lock) { action(SystemClock.elapsedRealtime()) }
        refreshTrigger()
        val s = synchronized(lock) { trigger.state }
        if (s is ScanTrigger.State.Armed && s.deadlineMs != null) startTicker()
        if (s is ScanTrigger.State.Cancelled) {
            showBanner(Banner(text(R.string.scan_cancelled)))
            scheduleAcknowledge(0)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val timedOut = synchronized(lock) { trigger.tick(SystemClock.elapsedRealtime()) }
                refreshTrigger()
                if (timedOut) {
                    feedback.failure()
                    showBanner(Banner(text(R.string.scan_no_code), Banner.Kind.ERROR))
                    scheduleAcknowledge(RESULT_DISPLAY_MS)
                    break
                }
                if (synchronized(lock) { trigger.state !is ScanTrigger.State.Armed }) break
            }
        }
    }

    private fun scheduleAcknowledge(delayMs: Long) {
        resetJob?.cancel()
        resetJob = viewModelScope.launch {
            delay(delayMs)
            synchronized(lock) { trigger.acknowledge() }
            refreshTrigger()
        }
    }

    private fun refreshTrigger() {
        val now = SystemClock.elapsedRealtime()
        val ui = synchronized(lock) {
            when (val s = trigger.state) {
                is ScanTrigger.State.Armed -> if (s.deadlineMs == null) TriggerUi.Continuous else {
                    val total = (_state.value.config?.scan?.trigger?.timeoutMs ?: 6000).toFloat()
                    TriggerUi.Armed(((trigger.remainingMs(now) ?: 0L) / total).coerceIn(0f, 1f))
                }
                is ScanTrigger.State.Success -> TriggerUi.Success
                ScanTrigger.State.Timeout -> TriggerUi.Failed
                ScanTrigger.State.Cancelled, ScanTrigger.State.Idle -> TriggerUi.Idle
            }
        }
        _state.update { it.copy(trigger = ui) }
    }

    // --- Saisie HID (scanner Bluetooth/USB, gâchette intégrée) ---------------------------------

    fun onHidChar(c: Char, timeMs: Long) {
        if (screenActive) hid.onChar(c, timeMs)
    }

    /** Entrée/Tab d'un scanner HID. Retourne `true` si une lecture a été reconnue (touche consommée). */
    fun onHidTerminator(timeMs: Long): Boolean {
        if (!screenActive) return false
        val code = hid.onTerminator(timeMs) ?: return false
        // Le scanner HID a sa propre gâchette : toujours accepté, filtré seulement contre les relectures.
        if (!synchronized(lock) { sameCode.accept(code, timeMs) }) return true
        viewModelScope.launch { handleRaw(code, fromCamera = false) }
        return true
    }

    // --- Traitement d'une lecture -------------------------------------------------------------

    private suspend fun handleRaw(raw: String, fromCamera: Boolean) {
        if (fromCamera) {
            refreshTrigger()
            scheduleAcknowledge(RESULT_DISPLAY_MS)
        }
        if (ConfigQr.isConfigQr(raw)) {
            feedback.success()
            showBanner(Banner(text(R.string.config_qr_detected), Banner.Kind.SUCCESS))
            _configQr.emit(raw.trim())
            return
        }
        val config = _state.value.config
        if (config == null) {
            feedback.failure()
            showBanner(Banner(text(R.string.onboarding_scan), Banner.Kind.ERROR))
            return
        }
        when (val parsed = ScanParser(config.scan).parse(raw)) {
            is ParseOutcome.Rejected -> {
                feedback.failure()
                showBanner(Banner(UiText.Raw(parsed.reason), Banner.Kind.ERROR))
            }
            is ParseOutcome.Ok -> {
                feedback.success()
                when (_state.value.mode) {
                    ScanMode.ITEM -> openItem(config, parsed.code)
                    ScanMode.BURST -> burstScan(config, parsed.code)
                }
            }
        }
    }

    // --- Mode Fiche ---------------------------------------------------------------------------

    private suspend fun openItem(config: KuraConfig, code: ScannedCode) {
        val fromScan = ItemMapper.fromScan(code.itemId, code.fields)
        val pane = ItemPaneState(
            code = code,
            stepper = PendingStepper(fromScan.quantity, allowSet = config.display.allowSet),
            stepButtons = config.display.stepButtons.ifEmpty { listOf(1) },
            item = fromScan,
            fields = ItemMapper.displayFields(fromScan, config),
        )
        _state.update { it.copy(item = pane, banner = Banner(text(R.string.scan_read, code.itemId), Banner.Kind.SUCCESS)) }

        val queryServer = config.scan.format == CodeFormat.ID || config.endpoints.getItem != null
        if (!queryServer) {
            updateItem(code) { it.copy(loading = false) }
            return
        }
        val cleartext = container.cleartextToConfirm()
        val lookup = if (cleartext != null) {
            _state.update { it.copy(cleartextPrompt = cleartext) }
            null
        } else {
            withContext(Dispatchers.IO) { container.client(config).getItem(code.fields, code.itemId) }
        }
        val requests = container.requests(config)
        when (lookup) {
            is ItemLookup.Found -> {
                val merged = ItemMapper.merge(fromScan, lookup.item)
                container.itemCache.put(merged)
                showItem(config, code, merged, offline = false, fromCache = false)
            }
            ItemLookup.NotFound -> updateItem(code) { it.copy(loading = false, notFound = true) }
            is ItemLookup.Failed -> {
                if (lookup.failure.kind == FailureKind.AUTH || lookup.failure.kind == FailureKind.CLIENT) {
                    showBanner(Banner(UiText.Raw(lookup.failure.message), Banner.Kind.ERROR))
                }
                offlineItem(config, code, fromScan)
            }
            null -> offlineItem(config, code, fromScan)
        }
        // En-têtes d'authentification pour l'image, seulement vers le serveur de l'entreprise.
        val url = _state.value.item?.takeIf { it.code == code }?.imageUrl
        if (url != null && requests.isSameOrigin(url)) {
            val headers = withContext(Dispatchers.IO) { requests.authHeaders() }
            updateItem(code) { it.copy(imageHeaders = headers) }
        }
    }

    private suspend fun offlineItem(config: KuraConfig, code: ScannedCode, fromScan: ItemInfo) {
        val cached = container.itemCache.get(code.itemId)
        if (cached != null) showItem(config, code, ItemMapper.merge(fromScan, cached), offline = true, fromCache = true)
        else updateItem(code) { it.copy(loading = false, offline = true) }
    }

    private fun showItem(config: KuraConfig, code: ScannedCode, item: ItemInfo, offline: Boolean, fromCache: Boolean) {
        val requests = container.requests(config)
        updateItem(code) {
            it.copy(
                loading = false,
                item = item,
                fields = ItemMapper.displayFields(item, config),
                imageUrl = requests.imageUrl(item, code.fields),
                offline = offline,
                fromCache = fromCache,
                stepper = it.stepper.reset(item.quantity),
            )
        }
    }

    private fun updateItem(code: ScannedCode, f: (ItemPaneState) -> ItemPaneState) {
        _state.update { s -> if (s.item?.code == code) s.copy(item = f(s.item)) else s }
    }

    fun onStep(amount: Int) = updateStepper { it.step(amount.toLong()) }

    fun onQuantityInput(value: Long?) = updateStepper { if (value == null) it.reset() else it.setQuantity(value) }

    private fun updateStepper(f: (PendingStepper) -> PendingStepper) {
        _state.update { s -> s.item?.let { s.copy(item = it.copy(stepper = f(it.stepper), result = null)) } ?: s }
    }

    fun closeItem() {
        _state.update { it.copy(item = null) }
    }

    fun onValidate() {
        val pane = _state.value.item ?: return
        val config = _state.value.config ?: return
        val op = try {
            pane.stepper.toOperation(pane.code.itemId, container.operationFactory)
        } catch (e: IllegalArgumentException) {
            setItemResult(pane.code, Banner(UiText.Raw(e.message ?: ""), Banner.Kind.ERROR)); return
        }
        if (op == null) {
            setItemResult(pane.code, Banner(text(R.string.item_nothing)))
            return
        }
        updateItem(pane.code) { it.copy(submitting = true) }
        viewModelScope.launch {
            container.operationStore.enqueue(op, pane.code.fields, pane.item?.name)
            val outcome = sendNow(config, op)
            val banner = when (outcome) {
                is OpOutcome.Sent -> when {
                    outcome.notice == Notice.SUPERSEDED -> Banner(text(R.string.item_superseded))
                    outcome.result.quantity != null -> Banner(text(R.string.item_sent_quantity, outcome.result.quantity!!), Banner.Kind.SUCCESS)
                    else -> Banner(text(R.string.item_sent), Banner.Kind.SUCCESS)
                }
                is OpOutcome.InError -> Banner(text(R.string.item_rejected, outcome.message ?: outcome.reason), Banner.Kind.ERROR)
                is OpOutcome.WillRetry, null -> Banner(text(R.string.item_queued))
            }
            if (outcome is OpOutcome.Sent) feedback.success() else if (outcome is OpOutcome.InError) feedback.failure()
            val newBase = (outcome as? OpOutcome.Sent)?.result?.quantity ?: pane.stepper.projectedQuantity
            updateItem(pane.code) {
                it.copy(
                    submitting = false,
                    result = banner,
                    stepper = it.stepper.reset(newBase),
                    item = it.item?.let { item -> if (newBase != null) item.copy(quantity = newBase, fields = item.fields + ("quantity" to newBase.toString())) else item },
                ).let { p -> p.copy(fields = p.item?.let { item -> ItemMapper.displayFields(item, config) } ?: p.fields) }
            }
        }
    }

    private fun setItemResult(code: ScannedCode, banner: Banner) = updateItem(code) { it.copy(result = banner) }

    /**
     * Envoi immédiat si la synchro est automatique et le réseau confirmé ; sinon l'opération reste en file.
     * Retourne le sort de [op], ou `null` si elle n'a pas été tentée.
     */
    private suspend fun sendNow(config: KuraConfig, op: Operation): OpOutcome? {
        if (config.sync.mode == SyncMode.MANUAL) return null
        container.cleartextToConfirm()?.let { url ->
            _state.update { it.copy(cleartextPrompt = url) }
            return null
        }
        val report = withContext(Dispatchers.IO) { container.syncEngine.drain() }
        report.quantities.forEach { (ref, q) -> container.itemCache.updateQuantity(ref, q) }
        report.retryInMs?.let { if (!report.authFailed) container.syncScheduler.scheduleRetry(it) }
        if (report.authFailed) showBanner(Banner(text(R.string.queue_auth_failed), Banner.Kind.ERROR))
        return report.outcomes[op.opId]
    }

    // --- Mode Rafale --------------------------------------------------------------------------

    private suspend fun burstScan(config: KuraConfig, code: ScannedCode) {
        if (!config.burst.enabled) {
            showBanner(Banner(text(R.string.burst_disabled), Banner.Kind.ERROR)); return
        }
        val s = session ?: newSession(config, _state.value.direction).also { session = it }
        s.direction = _state.value.direction
        s.step = _state.value.burstStep
        var label = code.fields["name"] ?: container.itemCache.get(code.itemId)?.name
        if (config.burst.verifyItem && container.cleartextToConfirm() == null) {
            when (val lookup = withContext(Dispatchers.IO) { container.client(config).getItem(code.fields, code.itemId) }) {
                ItemLookup.NotFound -> {
                    feedback.failure()
                    showBanner(Banner(text(R.string.burst_unknown_item, code.itemId), Banner.Kind.ERROR))
                    return
                }
                is ItemLookup.Found -> {
                    container.itemCache.put(lookup.item)
                    label = lookup.item.name ?: label
                }
                is ItemLookup.Failed -> Unit // hors-ligne : l'erreur éventuelle remontera à la synchro
            }
        }
        val event = s.onScan(code.itemId, label) as? BurstEvent.Created ?: return
        container.operationStore.enqueue(event.op, code.fields, label)
        showBanner(Banner(text(R.string.burst_feedback, label ?: code.itemId, signed(event.op.value)), Banner.Kind.SUCCESS))
        refreshBurst()
        syncInBackground(config)
    }

    fun undoLast() {
        val config = _state.value.config ?: return
        val s = session ?: return
        val last = s.lastUndoable ?: return
        viewModelScope.launch {
            val queued = container.operationStore.get(last.opId) != null
            when (val action = s.undo { queued }) {
                is UndoAction.RemoveQueued -> {
                    if (container.operationStore.removeIfPresent(action.op.opId)) {
                        showBanner(Banner(text(R.string.burst_undone)))
                    } else {
                        // Partie entre-temps : on compense.
                        val inverse = action.op.inverse(container.operationFactory)
                        container.operationStore.enqueue(inverse, emptyMap(), null)
                        s.restoreAsSent(action.op, inverse)
                        showBanner(Banner(text(R.string.burst_compensated)))
                        syncInBackground(config)
                    }
                }
                is UndoAction.Compensate -> {
                    container.operationStore.enqueue(action.inverse, emptyMap(), null)
                    showBanner(Banner(text(R.string.burst_compensated)))
                    syncInBackground(config)
                }
                UndoAction.Nothing -> Unit
            }
            refreshBurst()
        }
    }

    fun correctLine(itemId: String, targetTotal: Long) {
        val config = _state.value.config ?: return
        val op = session?.correctLine(itemId, targetTotal) ?: return
        viewModelScope.launch {
            container.operationStore.enqueue(op, mapOf("ref" to itemId), null)
            refreshBurst()
            syncInBackground(config)
        }
    }

    fun newBurstSession() {
        val config = _state.value.config ?: return
        session = newSession(config, _state.value.direction)
        refreshBurst()
    }

    private fun refreshBurst() {
        val s = session
        _state.update { it.copy(burstLines = s?.lines ?: emptyList(), canUndo = s?.lastUndoable != null) }
    }

    private fun syncInBackground(config: KuraConfig) {
        if (config.sync.mode == SyncMode.MANUAL) return
        if (container.cleartextToConfirm() != null) {
            _state.update { it.copy(cleartextPrompt = container.cleartextToConfirm()) }
            return
        }
        // WorkManager s'en charge (contrainte réseau) ; il partage le mutex du moteur de synchro.
        container.syncScheduler.requestNow()
    }

    // --- Divers -------------------------------------------------------------------------------

    fun setMode(mode: ScanMode) {
        if (mode == ScanMode.BURST && _state.value.config?.burst?.enabled == false) return
        _state.update { it.copy(mode = mode, item = if (mode == ScanMode.BURST) null else it.item) }
    }

    fun setDirection(direction: Direction) {
        _state.update { it.copy(direction = direction) }
        session?.direction = direction
    }

    fun toggleTorch() = _state.update { it.copy(torchOn = !it.torchOn) }

    fun onCameraCapabilities(hasTorch: Boolean, available: Boolean) =
        _state.update { it.copy(hasTorch = hasTorch, cameraAvailable = available, torchOn = it.torchOn && hasTorch) }

    fun acceptCleartext() {
        val url = _state.value.cleartextPrompt ?: return
        container.settings.acceptCleartext(url)
        _state.update { it.copy(cleartextPrompt = null) }
        container.syncScheduler.requestNow()
    }

    fun promptCleartext(url: String) = _state.update { it.copy(cleartextPrompt = url) }

    fun dismissCleartext() = _state.update { it.copy(cleartextPrompt = null) }

    private fun showBanner(banner: Banner) = _state.update { it.copy(banner = banner) }

    fun dismissBanner(id: Long) = _state.update { if (it.banner?.id == id) it.copy(banner = null) else it }

    override fun onCleared() {
        feedback.release()
    }

    private companion object {
        const val TICK_MS = 100L
        const val RESULT_DISPLAY_MS = 1200L
    }
}
