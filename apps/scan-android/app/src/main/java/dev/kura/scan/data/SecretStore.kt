package dev.kura.scan.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.kura.core.security.SecretEnvelope
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Secret d'authentification chiffré par une clé AES-GCM de l'AndroidKeyStore (API 23+),
 * stocké dans des SharedPreferences ordinaires (format [SecretEnvelope]).
 *
 * Remplace EncryptedSharedPreferences (déprécié, et instable sur certains keystores d'entrée de gamme).
 * En cas d'échec du keystore, une [SecretEnvelope.SecretStoreException] est levée : jamais de repli en clair.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("kura_secrets", Context.MODE_PRIVATE)

    @Volatile
    private var cached: String? = null

    @Volatile
    private var loaded = false

    /** Dernière erreur du stockage sécurisé, à afficher dans les Réglages. */
    @Volatile
    var lastError: String? = null
        private set

    @Throws(SecretEnvelope.SecretStoreException::class)
    fun write(secret: String?) {
        if (secret.isNullOrEmpty()) {
            prefs.edit().remove(ENTRY).apply()
            cached = null
            loaded = true
            return
        }
        val sealed = try {
            SecretEnvelope.seal(key(), ENTRY, secret)
        } catch (e: SecretEnvelope.SecretStoreException) {
            lastError = e.message
            throw e
        } catch (e: Exception) {
            // Keystore défaillant (OEM) : on le signale, sans jamais stocker le secret en clair.
            lastError = e.javaClass.simpleName
            throw SecretEnvelope.SecretStoreException("Keystore indisponible : ${e.javaClass.simpleName}", e)
        }
        prefs.edit().putString(ENTRY, sealed).apply()
        cached = secret
        loaded = true
        lastError = null
    }

    /** Secret courant, ou `null` (absent ou illisible ; voir [lastError]). */
    fun readOrNull(): String? {
        if (loaded) return cached
        val sealed = prefs.getString(ENTRY, null)
        cached = if (sealed == null) null else try {
            SecretEnvelope.open(key(), ENTRY, sealed).also { lastError = null }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
        loaded = true
        return cached
    }

    val hasSecret: Boolean get() = prefs.contains(ENTRY)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kura_secret_key_v1"
        const val ENTRY = "server_secret"
    }
}
