package dev.kura.core.security

import dev.kura.core.util.Base64Codec
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Format de stockage d'un secret chiffré (jeton, mot de passe basic, clé API) :
 * `v1:` + base64(iv12 | AES-GCM(secret) avec tag 128 bits), avec le nom de l'entrée comme données associées (AAD),
 * pour qu'un chiffré ne puisse pas être recopié d'une entrée à l'autre.
 *
 * Sur Android, la clé vit dans l'AndroidKeyStore : c'est lui qui choisit l'IV (chiffrement randomisé imposé),
 * d'où `init` sans IV puis lecture de `cipher.iv`. La même logique fonctionne avec une clé JDK (tests).
 */
object SecretEnvelope {
    const val VERSION_PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

    @Throws(SecretStoreException::class)
    fun seal(key: SecretKey, entryName: String, secret: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(entryName.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        if (iv == null || iv.size != IV_LEN) throw SecretStoreException("IV inattendu (${iv?.size ?: 0} octets)")
        VERSION_PREFIX + Base64Codec.encode(iv + ct)
    } catch (e: GeneralSecurityException) {
        throw SecretStoreException("Chiffrement du secret impossible : ${e.javaClass.simpleName}", e)
    } catch (e: IllegalStateException) {
        throw SecretStoreException("Chiffrement du secret impossible : ${e.javaClass.simpleName}", e)
    }

    @Throws(SecretStoreException::class)
    fun open(key: SecretKey, entryName: String, sealed: String): String {
        if (!sealed.startsWith(VERSION_PREFIX)) throw SecretStoreException("Format de secret inconnu")
        val bytes = try {
            Base64Codec.decode(sealed.removePrefix(VERSION_PREFIX))
        } catch (e: IllegalArgumentException) {
            throw SecretStoreException("Secret stocké illisible", e)
        }
        if (bytes.size < IV_LEN + TAG_BITS / 8) throw SecretStoreException("Secret stocké tronqué")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_LEN))
            cipher.updateAAD(entryName.toByteArray(Charsets.UTF_8))
            cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw SecretStoreException("Déchiffrement du secret impossible (clé changée ou donnée altérée)", e)
        }
    }
}
