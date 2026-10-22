package dev.kura.scan.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kura.core.config.KuraConfig
import dev.kura.core.security.Argon2Phc
import dev.kura.core.security.SecretEnvelope
import dev.kura.scan.R
import dev.kura.scan.kura
import dev.kura.scan.ui.common.UiText
import dev.kura.scan.ui.common.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val config: KuraConfig? = null,
    val warnings: List<String> = emptyList(),
    val locked: Boolean = false,
    val protectedByConfig: Boolean = false,
    val hasLocalLock: Boolean = false,
    val deviceId: String = "",
    val user: String? = null,
)

data class SettingsTransient(
    val unlockBusy: Boolean = false,
    val unlockWrong: Boolean = false,
    val message: UiText? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.kura
    private val settings = container.settings

    private val _transient = MutableStateFlow(SettingsTransient())

    private val _hasSecret = MutableStateFlow(container.secrets.hasSecret)
    val hasSecret: StateFlow<Boolean> = _hasSecret.asStateFlow()
    val transient: StateFlow<SettingsTransient> = _transient.asStateFlow()

    val state: StateFlow<SettingsUiState> = combine(
        settings.config, settings.configWarnings, settings.localLockHash, container.lock.unlocked, settings.user,
    ) { config, warnings, localHash, _, user ->
        SettingsUiState(
            config = config,
            warnings = warnings,
            locked = container.lock.isLocked(config),
            protectedByConfig = config?.security?.configPasswordHash != null,
            hasLocalLock = localHash != null,
            deviceId = settings.deviceId,
            user = user,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun unlock(password: String) {
        _transient.update { it.copy(unlockBusy = true, unlockWrong = false) }
        viewModelScope.launch {
            val ok = container.lock.unlock(password, settings.config.value)
            _transient.update { it.copy(unlockBusy = false, unlockWrong = !ok) }
        }
    }

    fun relock() = container.lock.relock()

    fun saveSecret(secret: String) {
        viewModelScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) { container.secrets.write(secret.trim()) }
                container.syncScheduler.requestNow()
                text(R.string.settings_secret_saved)
            } catch (e: SecretEnvelope.SecretStoreException) {
                text(R.string.settings_secret_error, e.message ?: "")
            }
            _hasSecret.value = container.secrets.hasSecret
            _transient.update { it.copy(message = msg) }
        }
    }

    fun clearSecret() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { container.secrets.write(null) } }
            _hasSecret.value = container.secrets.hasSecret
        }
    }

    fun setDeviceId(value: String) {
        if (value.isNotBlank()) settings.deviceId = value
    }

    fun setUser(value: String) = settings.setUser(value)

    /** Mot de passe local (si la config n'en impose pas), hashé en argon2id sur l'appareil. */
    fun setLocalPassword(password: String) {
        viewModelScope.launch {
            val hash = withContext(Dispatchers.Default) { Argon2Phc.hash(password) }
            settings.setLocalLockHash(hash)
            container.lock.relock()
            _transient.update { it.copy(message = text(R.string.settings_lock_enabled)) }
        }
    }

    fun removeLocalPassword() = settings.setLocalLockHash(null)

    fun consumeMessage() = _transient.update { it.copy(message = null) }
}
