package dev.kura.core.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Hash argon2id au format PHC (`$argon2id$v=19$m=19456,t=2,p=1$<sel>$<hash>`, base64 sans remplissage),
 * calculé avec l'implémentation pur Java de Bouncy Castle. Utilisé pour le mot de passe de verrouillage
 * (`security.config_password_hash`, produit par Kura Studio, ou défini sur l'appareil).
 */
object Argon2Phc {
    data class Params(
        val memoryKiB: Int = 19456,
        val iterations: Int = 2,
        val parallelism: Int = 1,
        val version: Int = 0x13,
        val hashLength: Int = 32,
    )

    data class Parsed(val params: Params, val salt: ByteArray, val hash: ByteArray)

    /** Bornes de sécurité : refuse un hash qui ferait exploser la mémoire ou le temps de calcul sur le téléphone. */
    private const val MAX_MEMORY_KIB = 262_144
    private const val MAX_ITERATIONS = 20
    private const val MAX_PARALLELISM = 16

    private val B64_ENC = Base64.getEncoder().withoutPadding()
    private val B64_DEC = Base64.getDecoder()

    fun hash(password: String, params: Params = Params(), salt: ByteArray = randomSalt()): String {
        val out = derive(password, salt, params)
        return "\$argon2id\$v=${params.version}\$m=${params.memoryKiB},t=${params.iterations},p=${params.parallelism}" +
            "\$${B64_ENC.encodeToString(salt)}\$${B64_ENC.encodeToString(out)}"
    }

    /** Vérifie un mot de passe. Retourne `false` (sans exception) si le hash est mal formé. */
    fun verify(password: String, phc: String): Boolean {
        val parsed = parse(phc) ?: return false
        val actual = derive(password, parsed.salt, parsed.params.copy(hashLength = parsed.hash.size))
        return MessageDigest.isEqual(actual, parsed.hash)
    }

    /** Analyse une chaîne PHC argon2id (le `$` initial est facultatif, comme le permet le schéma). */
    fun parse(phc: String): Parsed? {
        val parts = phc.trim().removePrefix("$").split('$')
        if (parts.isEmpty() || parts[0] != "argon2id") return null
        var i = 1
        var version = 0x10
        if (parts.getOrNull(i)?.startsWith("v=") == true) {
            version = parts[i].removePrefix("v=").toIntOrNull() ?: return null
            i++
        }
        if (version != 0x10 && version != 0x13) return null
        val paramMap = parts.getOrNull(i)?.split(',')?.associate {
            val kv = it.split('=', limit = 2)
            if (kv.size != 2) return null
            kv[0] to (kv[1].toIntOrNull() ?: return null)
        } ?: return null
        i++
        val m = paramMap["m"] ?: return null
        val t = paramMap["t"] ?: return null
        val p = paramMap["p"] ?: return null
        if (m < 8 * p || m > MAX_MEMORY_KIB || t !in 1..MAX_ITERATIONS || p !in 1..MAX_PARALLELISM) return null
        if (parts.size != i + 2) return null
        val salt = decode(parts[i]) ?: return null
        val hash = decode(parts[i + 1]) ?: return null
        if (salt.size < 8 || hash.size < 4) return null
        return Parsed(Params(m, t, p, version, hash.size), salt, hash)
    }

    private fun decode(s: String): ByteArray? = try {
        B64_DEC.decode(s.trimEnd('='))
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun derive(password: String, salt: ByteArray, params: Params): ByteArray {
        val bcParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(params.version)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(bcParams)
        val out = ByteArray(params.hashLength)
        gen.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }

    private fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
}
