package dev.kura.scan.data

import dev.kura.core.config.KuraConfig
import dev.kura.core.security.Argon2Phc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Verrouillage des Réglages et du changement de config.
 * Hash : `security.config_password_hash` (Kura Studio) en priorité, sinon celui défini sur l'appareil.
 * Déverrouillé pour la durée du processus seulement.
 */
class SessionLock(private val settings: SettingsRepository) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun hashFor(config: KuraConfig?): String? = config?.security?.configPasswordHash ?: settings.localLockHash.value

    /** Un mot de passe existe (config ou appareil). Une config `config_locked` sans hash n'est pas verrouillable. */
    fun isProtected(config: KuraConfig?): Boolean = hashFor(config) != null

    fun isLocked(config: KuraConfig?): Boolean = isProtected(config) && !_unlocked.value

    /** Vérification argon2id hors du fil principal (≈ 0,1 à 1 s selon l'appareil). */
    suspend fun unlock(password: String, config: KuraConfig?): Boolean {
        val hash = hashFor(config) ?: return true
        val ok = withContext(Dispatchers.Default) { Argon2Phc.verify(password, hash) }
        if (ok) _unlocked.value = true
        return ok
    }

    fun relock() {
        _unlocked.value = false
    }
}
