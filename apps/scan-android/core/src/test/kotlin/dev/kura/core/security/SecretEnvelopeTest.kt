package dev.kura.core.security

import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretEnvelopeTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `aller-retour et IV aleatoire`() {
        val a = SecretEnvelope.seal(key, "server_secret", "jeton é")
        val b = SecretEnvelope.seal(key, "server_secret", "jeton é")
        assertTrue(a.startsWith("v1:"))
        assertNotEquals(a, b)
        assertEquals("jeton é", SecretEnvelope.open(key, "server_secret", a))
    }

    @Test
    fun `entree differente, cle differente ou alteration refusees`() {
        val sealed = SecretEnvelope.seal(key, "server_secret", "tok")
        assertFailsWith<SecretEnvelope.SecretStoreException> { SecretEnvelope.open(key, "autre", sealed) }
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertFailsWith<SecretEnvelope.SecretStoreException> { SecretEnvelope.open(other, "server_secret", sealed) }
        val tampered = sealed.dropLast(2) + (if (sealed.endsWith("AA")) "AB" else "AA")
        assertFailsWith<SecretEnvelope.SecretStoreException> { SecretEnvelope.open(key, "server_secret", tampered) }
        assertFailsWith<SecretEnvelope.SecretStoreException> { SecretEnvelope.open(key, "server_secret", "tok") }
        assertFailsWith<SecretEnvelope.SecretStoreException> { SecretEnvelope.open(key, "server_secret", "v1:AAAA") }
    }
}
