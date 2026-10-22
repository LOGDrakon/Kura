package dev.kura.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Argon2PhcTest {
    // Vecteurs produits par l'implémentation de référence (argon2-cffi / libargon2).
    private val ref1 = "\$argon2id\$v=19\$m=1024,t=2,p=2\$a3VyYXNhbHRrdXJhc2FsdA\$JeqCcHDic3qecvFdRDi2Q+TBcFkEitnMaaQTiKL09Tk"
    private val ref2 = "\$argon2id\$v=19\$m=512,t=1,p=1\$MDEyMzQ1Njc4OWFiY2RlZg\$Kt9BkOYvUH1+569utb4e4A"

    @Test
    fun `verifie les vecteurs de reference`() {
        assertTrue(Argon2Phc.verify("correct horse", ref1))
        assertFalse(Argon2Phc.verify("correct horse!", ref1))
        assertTrue(Argon2Phc.verify("Mot de passe é", ref2))
        assertFalse(Argon2Phc.verify("Mot de passe e", ref2))
    }

    @Test
    fun `parametres lus depuis la chaine`() {
        val p = assertNotNull(Argon2Phc.parse(ref1))
        assertEquals(1024, p.params.memoryKiB)
        assertEquals(2, p.params.iterations)
        assertEquals(2, p.params.parallelism)
        assertEquals(19, p.params.version)
        assertEquals(32, p.hash.size)
        assertEquals("kurasaltkurasalt", String(p.salt))
        assertEquals(16, Argon2Phc.parse(ref2)!!.params.hashLength)
    }

    @Test
    fun `aller-retour avec les parametres de Kura Studio`() {
        val phc = Argon2Phc.hash("verrou-atelier")
        assertTrue(phc.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        val parsed = assertNotNull(Argon2Phc.parse(phc))
        assertEquals(16, parsed.salt.size)
        assertEquals(32, parsed.hash.size)
        assertFalse(phc.substringAfter("p=1").contains('='))
        assertTrue(Argon2Phc.verify("verrou-atelier", phc))
        assertFalse(Argon2Phc.verify("verrou-atelieR", phc))
    }

    @Test
    fun `dollar initial facultatif comme dans le schema`() {
        assertTrue(Argon2Phc.verify("correct horse", ref1.removePrefix("$")))
    }

    @Test
    fun `chaines mal formees ou dangereuses refusees`() {
        assertNull(Argon2Phc.parse("\$argon2i\$v=19\$m=1024,t=2,p=2\$c2FsdHNhbHQ\$aGFzaA"))
        assertNull(Argon2Phc.parse("\$argon2id\$v=19\$m=1024,t=2\$c2FsdHNhbHQ\$aGFzaGhhc2g"))
        assertNull(Argon2Phc.parse("\$argon2id\$v=19\$m=4194304,t=2,p=1\$c2FsdHNhbHQ\$aGFzaGhhc2g"))
        assertNull(Argon2Phc.parse("\$argon2id\$v=19\$m=1024,t=2,p=1\$!!\$aGFzaGhhc2g"))
        assertNull(Argon2Phc.parse("secret123"))
        assertFalse(Argon2Phc.verify("x", "n'importe quoi"))
        // L'exemple de spec/examples/kura-config.full.json est un hash factice : bien formé mais ne correspond à rien.
        val example = "\$argon2id\$v=19\$m=65536,t=3,p=4\$c2FsdHNhbHRzYWx0\$5u0yG9pXq1o7m8Ww8d0mPq2rWkV8sF5aYbC3dE4fG6h"
        assertNotNull(Argon2Phc.parse(example))
    }
}
