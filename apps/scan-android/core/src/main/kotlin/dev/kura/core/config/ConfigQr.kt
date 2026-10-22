package dev.kura.core.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Contenu d'un QR de configuration, une fois décodé. */
data class ConfigQrPayload(val configJson: JsonElement, val secret: String?)

sealed interface ConfigQrResult {
    data class Ok(val payload: ConfigQrPayload) : ConfigQrResult

    /** QR chiffré (`KURA1E:`) : demander le code PIN puis rappeler [ConfigQr.decode] avec. */
    data object PinRequired : ConfigQrResult

    data object WrongPin : ConfigQrResult

    data class Invalid(val reason: String) : ConfigQrResult
}

/**
 * QR de configuration (format décrit dans `spec/config-qr.md`) :
 * - `KURA1:`  + base64url(JSON)                          — jamais de secret ;
 * - `KURA1E:` + base64url(sel16 | iv12 | AES-256-GCM)    — clé = PBKDF2-HMAC-SHA256(PIN, sel, 120 000, 256 bits).
 * JSON = `{ "config": {…}, "secret": "…" }`. Uniquement des API JDK (javax.crypto), disponibles sur Android.
 */
object ConfigQr {
    const val PLAIN_PREFIX = "KURA1:"
    const val ENCRYPTED_PREFIX = "KURA1E:"
    const val PBKDF2_ITERATIONS = 120_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val json = Json { ignoreUnknownKeys = true }
    private val B64URL_ENC = Base64.getUrlEncoder().withoutPadding()
    private val B64URL_DEC = Base64.getUrlDecoder()

    fun isConfigQr(text: String): Boolean {
        val t = text.trim()
        return t.startsWith(PLAIN_PREFIX) || t.startsWith(ENCRYPTED_PREFIX)
    }

    fun isEncrypted(text: String): Boolean = text.trim().startsWith(ENCRYPTED_PREFIX)

    fun decode(text: String, pin: String? = null): ConfigQrResult {
        val t = text.trim()
        return when {
            t.startsWith(ENCRYPTED_PREFIX) -> decodeEncrypted(t.removePrefix(ENCRYPTED_PREFIX), pin)
            t.startsWith(PLAIN_PREFIX) -> {
                val bytes = b64(t.removePrefix(PLAIN_PREFIX)) ?: return ConfigQrResult.Invalid("QR de configuration abîmé (base64).")
                when (val r = payload(bytes)) {
                    is ConfigQrResult.Ok -> if (r.payload.secret != null) {
                        ConfigQrResult.Invalid("QR refusé : il contient un secret en clair. Générez un QR chiffré par code PIN.")
                    } else r
                    else -> r
                }
            }
            else -> ConfigQrResult.Invalid("Ce code n'est pas un QR de configuration Kura.")
        }
    }

    private fun decodeEncrypted(body: String, pin: String?): ConfigQrResult {
        val bytes = b64(body) ?: return ConfigQrResult.Invalid("QR de configuration abîmé (base64).")
        if (bytes.size < SALT_LEN + IV_LEN + TAG_BITS / 8) return ConfigQrResult.Invalid("QR de configuration tronqué.")
        if (pin.isNullOrEmpty()) return ConfigQrResult.PinRequired
        val salt = bytes.copyOfRange(0, SALT_LEN)
        val iv = bytes.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ct = bytes.copyOfRange(SALT_LEN + IV_LEN, bytes.size)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(pin, salt), "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            return ConfigQrResult.WrongPin
        }
        return payload(plain)
    }

    private fun payload(bytes: ByteArray): ConfigQrResult {
        val obj = try {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return ConfigQrResult.Invalid("QR de configuration illisible (JSON).")
        val config = obj["config"] as? JsonObject ?: return ConfigQrResult.Invalid("QR de configuration sans « config ».")
        val secretEl = obj["secret"]
        val secret = when {
            secretEl == null || secretEl is kotlinx.serialization.json.JsonNull -> null
            secretEl is JsonPrimitive && secretEl.isString -> secretEl.content.takeIf { it.isNotEmpty() }
            else -> return ConfigQrResult.Invalid("QR de configuration : « secret » doit être un texte.")
        }
        return ConfigQrResult.Ok(ConfigQrPayload(config, secret))
    }

    /** Encode un QR (utile aux tests et à l'outillage ; Kura Studio implémente le même format). */
    fun encode(
        configJson: JsonObject,
        secret: String? = null,
        pin: String? = null,
        salt: ByteArray = random(SALT_LEN),
        iv: ByteArray = random(IV_LEN),
    ): String {
        val payload = buildMap<String, JsonElement> {
            put("config", configJson)
            secret?.let { put("secret", JsonPrimitive(it)) }
        }
        val plain = JsonObject(payload).toString().toByteArray(Charsets.UTF_8)
        if (pin.isNullOrEmpty()) {
            require(secret == null) { "un secret ne peut être inclus que dans un QR chiffré (PIN)" }
            return PLAIN_PREFIX + B64URL_ENC.encodeToString(plain)
        }
        require(salt.size == SALT_LEN && iv.size == IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(pin, salt), "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        return ENCRYPTED_PREFIX + B64URL_ENC.encodeToString(salt + iv + ct)
    }

    /** PBKDF2-HMAC-SHA256 sur les octets UTF-8 du PIN (implémenté sur `Mac` pour un comportement identique partout). */
    fun deriveKey(pin: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray =
        pbkdf2Sha256(pin.toByteArray(Charsets.UTF_8), salt, iterations, 32)

    internal fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Une clé HMAC vide est refusée par SecretKeySpec : un PIN vide est de toute façon rejeté en amont.
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (keyLen + hLen - 1) / hLen
        val out = ByteArray(keyLen)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val offset = (block - 1) * hLen
            System.arraycopy(t, 0, out, offset, minOf(hLen, keyLen - offset))
        }
        return out
    }

    private fun b64(s: String): ByteArray? = try {
        B64URL_DEC.decode(s.trim().trimEnd('='))
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
}
