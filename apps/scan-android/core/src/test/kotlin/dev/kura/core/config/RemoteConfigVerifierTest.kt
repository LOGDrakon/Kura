package dev.kura.core.config

import dev.kura.core.util.Base64Codec
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteConfigVerifierTest {
    private val config = """{"kura_config_version":1,"server":{"base_url":"https://a.fr"},"scan":{}}"""

    @Test
    fun `signature JDK (SPKI) acceptee, alteration refusee`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val spki = Base64Codec.encode(kp.public.encoded)
        val sig = Signature.getInstance("Ed25519").run {
            initSign(kp.private); update(config.toByteArray()); Base64Codec.encode(sign())
        }
        assertTrue(RemoteConfigVerifier.verify(config, sig, spki))
        assertFalse(RemoteConfigVerifier.verify(config.replace("a.fr", "b.fr"), sig, spki))
        assertFalse(RemoteConfigVerifier.verify(config, null, spki))
        assertFalse(RemoteConfigVerifier.verify(config, sig, null))
        assertFalse(RemoteConfigVerifier.verify(config, "pas-du-base64!", spki))
    }

    @Test
    fun `cle brute de 32 octets toleree`() {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        val kp = gen.generateKeyPair()
        val signer = Ed25519Signer().apply {
            init(true, kp.private as Ed25519PrivateKeyParameters); update(config.toByteArray(), 0, config.length)
        }
        val sig = Base64Codec.encode(signer.generateSignature())
        val raw = Base64Codec.encode((kp.public as Ed25519PublicKeyParameters).encoded)
        assertTrue(RemoteConfigVerifier.verify(config, sig, raw))
    }
}
