package dev.kura.scan.data

import android.content.Context
import dev.kura.core.config.ConfigParseResult
import dev.kura.core.config.ConfigParser
import dev.kura.core.config.KuraConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Réglages non secrets (SharedPreferences) : config courante, identité, confirmations.
 * Les secrets sont dans [SecretStore].
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("kura_settings", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<KuraConfig?> = _config.asStateFlow()

    private val _warnings = MutableStateFlow(loadWarnings())
    val configWarnings: StateFlow<List<String>> = _warnings.asStateFlow()

    private val _user = MutableStateFlow(prefs.getString(KEY_USER, null))
    val user: StateFlow<String?> = _user.asStateFlow()

    private fun loadConfig(): KuraConfig? =
        prefs.getString(KEY_CONFIG, null)?.let { ConfigParser.parse(it).getOrNull() }

    private fun loadWarnings(): List<String> =
        prefs.getString(KEY_CONFIG, null)?.let { ConfigParser.parse(it).warnings } ?: emptyList()

    /** Enregistre une config déjà validée (texte JSON d'origine). */
    fun saveConfig(json: String): Boolean {
        val result = ConfigParser.parse(json) as? ConfigParseResult.Valid ?: return false
        prefs.edit().putString(KEY_CONFIG, json).apply()
        _config.value = result.config
        _warnings.value = result.warnings
        return true
    }

    /** Identifiant stable de l'appareil (modifiable dans les Réglages). */
    var deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: ("android-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }
        set(value) {
            prefs.edit().putString(KEY_DEVICE, value.trim()).apply()
        }

    fun setUser(value: String?) {
        val v = value?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().putString(KEY_USER, v).apply()
        _user.value = v
    }

    /** Serveur HTTP (non chiffré) dont l'utilisateur a accepté l'avertissement. */
    fun isCleartextAccepted(baseUrl: String): Boolean = prefs.getString(KEY_CLEARTEXT, null) == baseUrl

    fun acceptCleartext(baseUrl: String) {
        prefs.edit().putString(KEY_CLEARTEXT, baseUrl).apply()
    }

    private val _localLockHash = MutableStateFlow(prefs.getString(KEY_LOCK, null))

    /** Hash argon2id du mot de passe de verrouillage défini sur l'appareil (si la config n'en fournit pas). */
    val localLockHash: StateFlow<String?> = _localLockHash.asStateFlow()

    fun setLocalLockHash(value: String?) {
        prefs.edit().putString(KEY_LOCK, value).apply()
        _localLockHash.value = value
    }

    private companion object {
        const val KEY_CONFIG = "config_json"
        const val KEY_DEVICE = "device_id"
        const val KEY_USER = "user"
        const val KEY_CLEARTEXT = "cleartext_accepted_for"
        const val KEY_LOCK = "local_lock_hash"
    }
}
