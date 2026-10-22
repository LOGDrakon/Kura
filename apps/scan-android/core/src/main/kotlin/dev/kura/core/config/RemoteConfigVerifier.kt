package dev.kura.core.config

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import dev.kura.core.util.Base64Codec

/**
 * Vérifie la signature Ed25519 d'une config distante (`GET /config`) avec `remote_config.signing_public_key`
 * (SPKI DER en base64). La signature porte sur les octets UTF-8 exacts du champ `config`.
 */
object RemoteConfigVerifier {
    /** En-tête DER d'une SubjectPublicKeyInfo Ed25519 (OID 1.3.101.112), suivi des 32 octets de clé. */
    private val ED25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    fun verify(config: String, signatureB64: String?, publicKeySpkiB64: String?): Boolean {
        if (signatureB64.isNullOrBlank() || publicKeySpkiB64.isNullOrBlank()) return false
        return try {
            val spki = Base64Codec.decode(publicKeySpkiB64.trim())
            val raw = when {
                spki.size == 44 && spki.copyOfRange(0, 12).contentEquals(ED25519_SPKI_PREFIX) -> spki.copyOfRange(12, 44)
                spki.size == 32 -> spki // clé brute tolérée
                else -> return false
            }
            val sig = Base64Codec.decode(signatureB64.trim())
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(raw, 0))
            val data = config.toByteArray(Charsets.UTF_8)
            signer.update(data, 0, data.size)
            signer.verifySignature(sig)
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
