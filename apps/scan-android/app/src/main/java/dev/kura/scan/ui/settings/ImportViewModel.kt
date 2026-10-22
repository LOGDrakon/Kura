package dev.kura.scan.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kura.core.config.ConfigParseResult
import dev.kura.core.config.ConfigParser
import dev.kura.core.config.ConfigQr
import dev.kura.core.config.ConfigQrResult
import dev.kura.core.config.KuraConfig
import dev.kura.core.security.SecretEnvelope
import dev.kura.scan.R
import dev.kura.scan.kura
import dev.kura.scan.ui.common.UiText
import dev.kura.scan.ui.common.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportState {
    data object None : ImportState
    data class NeedPin(val qr: String, val wrong: Boolean = false, val busy: Boolean = false) : ImportState
    data class Invalid(val errors: List<String>) : ImportState
    data class Confirm(val json: String, val config: KuraConfig, val warnings: List<String>, val secret: String?) : ImportState
    data class NeedUnlock(val confirm: Confirm, val wrong: Boolean = false, val busy: Boolean = false) : ImportState
    data class Done(val message: UiText) : ImportState
}

/** Import d'une config : QR (`KURA1:` / `KURA1E:`, voir spec/config-qr.md) ou fichier JSON. */
class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.kura
    private val _state = MutableStateFlow<ImportState>(ImportState.None)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun onQrText(qr: String) {
        when (val r = ConfigQr.decode(qr)) {
            ConfigQrResult.PinRequired -> _state.value = ImportState.NeedPin(qr)
            else -> handleQrResult(qr, r)
        }
    }

    fun onPin(pin: String) {
        val current = _state.value as? ImportState.NeedPin ?: return
        _state.value = current.copy(busy = true, wrong = false)
        viewModelScope.launch {
            // PBKDF2 120 000 itérations : plusieurs centaines de ms sur un téléphone modeste.
            val r = withContext(Dispatchers.Default) { ConfigQr.decode(current.qr, pin) }
            handleQrResult(current.qr, r)
        }
    }

    private fun handleQrResult(qr: String, r: ConfigQrResult) {
        _state.value = when (r) {
            is ConfigQrResult.Ok -> validate(r.payload.configJson.toString(), r.payload.secret)
            ConfigQrResult.WrongPin -> ImportState.NeedPin(qr, wrong = true)
            ConfigQrResult.PinRequired -> ImportState.NeedPin(qr)
            is ConfigQrResult.Invalid -> ImportState.Invalid(listOf(r.reason))
        }
    }

    fun onFile(uri: Uri) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        // Une config fait quelques Ko : on borne la lecture.
                        String(input.readBytes().let { if (it.size > MAX_FILE) it.copyOf(MAX_FILE) else it }, Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            _state.value = if (content == null) ImportState.Done(text(R.string.import_file_error))
            else if (ConfigQr.isConfigQr(content)) {
                onQrText(content); return@launch
            } else validate(content, null)
        }
    }

    private fun validate(json: String, secret: String?): ImportState = when (val r = ConfigParser.parse(json)) {
        is ConfigParseResult.Valid -> ImportState.Confirm(json, r.config, r.warnings, secret)
        is ConfigParseResult.Invalid -> ImportState.Invalid(r.errors)
    }

    fun onConfirm() {
        val confirm = _state.value as? ImportState.Confirm ?: return
        if (container.lock.isLocked(container.settings.config.value)) {
            _state.value = ImportState.NeedUnlock(confirm)
        } else {
            apply(confirm)
        }
    }

    fun onUnlock(password: String) {
        val s = _state.value as? ImportState.NeedUnlock ?: return
        _state.value = s.copy(busy = true, wrong = false)
        viewModelScope.launch {
            if (container.lock.unlock(password, container.settings.config.value)) apply(s.confirm)
            else _state.value = s.copy(busy = false, wrong = true)
        }
    }

    private fun apply(confirm: ImportState.Confirm) {
        viewModelScope.launch {
            val saved = container.settings.saveConfig(confirm.json)
            if (!saved) {
                _state.value = ImportState.Invalid(listOf("Configuration invalide."))
                return@launch
            }
            container.lock.relock()
            val message = if (confirm.secret != null) {
                try {
                    withContext(Dispatchers.IO) { container.secrets.write(confirm.secret) }
                    text(R.string.import_applied)
                } catch (e: SecretEnvelope.SecretStoreException) {
                    text(R.string.settings_secret_error, e.message ?: "")
                }
            } else {
                text(R.string.import_applied)
            }
            _state.value = ImportState.Done(message)
            container.syncScheduler.requestNow()
        }
    }

    fun dismiss() {
        _state.value = ImportState.None
    }

    private companion object {
        const val MAX_FILE = 256 * 1024
    }
}
