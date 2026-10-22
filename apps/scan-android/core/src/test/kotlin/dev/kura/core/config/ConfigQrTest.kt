package dev.kura.core.config

import dev.kura.core.util.Base64Codec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigQrTest {
    private val config: JsonObject = Json.parseToJsonElement(
        """{"kura_config_version":1,"server":{"base_url":"https://gmao.example.fr/api","auth":{"type":"bearer"}},"scan":{"format":"id"}}""",
    ).jsonObject

    // Vecteurs de spec/config-qr.md, générés indépendamment (Python `cryptography` + hashlib).
    private val vectorPin = "482913"
    private val vectorSalt = ByteArray(16) { it.toByte() }
    private val vectorIv = ByteArray(12) { (0x10 + it).toByte() }
    private val vectorPlain =
        """{"config":{"kura_config_version":1,"server":{"base_url":"https://gmao.example.fr/api","auth":{"type":"bearer"}},"scan":{"format":"id"}},"secret":"jeton-de-démo"}"""
    private val vectorEncrypted =
        "KURA1E:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG03Z2DfWlrQsxWg1ahW1gxAi655SsboV_5KQ-U899HgrnDDmMaqtV16WA5IuZB4FTnRBvRlmTPOYXiEL9nQ4fJLU8VrixdrWEZSe_abTvSMbKYqK8ev5LgDUbx9Cf83qTeEHwZDZVVavO4J7SmMUzoMXKW4E_H5m90_8kjBon6oL8Zx7yhwhFtwhg4yI8aDNnWUejjgNr3e-X-mR9X57pPMeZac3cTkWiXkWPypjzoCEmbg"
    private val vectorPlainQr =
        "KURA1:eyJjb25maWciOnsia3VyYV9jb25maWdfdmVyc2lvbiI6MSwic2VydmVyIjp7ImJhc2VfdXJsIjoiaHR0cDovLzEwLjAuMi4yOjg3ODcifSwic2NhbiI6eyJmb3JtYXQiOiJpZCJ9fX0"

    @Test
    fun `vecteur chiffre de la spec - dechiffrement`() {
        val ok = assertIs<ConfigQrResult.Ok>(ConfigQr.decode(vectorEncrypted, vectorPin))
        assertEquals("jeton-de-démo", ok.payload.secret)
        assertEquals(config, ok.payload.configJson)
        assertEquals(ConfigQrResult.WrongPin, ConfigQr.decode(vectorEncrypted, "482914"))
        val parsed = assertIs<ConfigParseResult.Valid>(ConfigParser.parse(ok.payload.configJson))
        assertEquals(AuthType.BEARER, parsed.config.server.auth.type)
    }

    @Test
    fun `vecteur chiffre de la spec - chiffrement identique`() {
        assertEquals(vectorEncrypted, ConfigQr.encryptRaw(vectorPlain.toByteArray(), vectorPin, vectorSalt, vectorIv))
    }

    @Test
    fun `vecteur en clair de la spec`() {
        val ok = assertIs<ConfigQrResult.Ok>(ConfigQr.decode(vectorPlainQr))
        assertNull(ok.payload.secret)
        assertEquals("http://10.0.2.2:8787", ok.payload.configJson.jsonObject["server"]!!.jsonObject["base_url"].toString().trim('"'))
    }

    @Test
    fun `cle derivee du vecteur`() {
        val key = ConfigQr.deriveKey(vectorPin, vectorSalt)
        assertEquals("7f3e4e9a0fc86393633ab7b1b8b620722f005b943bcd74a639c5c29f5dd3e166", key.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `PBKDF2 conforme RFC 7914 et au JDK`() {
        val dk = ConfigQr.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            dk.joinToString("") { "%02x".format(it) },
        )
        val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("1234".toCharArray(), vectorSalt, 1000, 256)).encoded
        assertTrue(jdk.contentEquals(ConfigQr.deriveKey("1234", vectorSalt, 1000)))
    }

    @Test
    fun `aller-retour en clair`() {
        val qr = ConfigQr.encode(config)
        assertTrue(qr.startsWith("KURA1:"))
        assertTrue(!qr.contains('=') && !qr.contains('+') && !qr.contains('/'))
        val ok = assertIs<ConfigQrResult.Ok>(ConfigQr.decode(qr))
        assertEquals(config, ok.payload.configJson)
    }

    @Test
    fun `aller-retour chiffre, PIN requis, mauvais PIN`() {
        val qr = ConfigQr.encode(config, secret = "tok", pin = "0000")
        assertTrue(ConfigQr.isEncrypted(qr))
        assertEquals(ConfigQrResult.PinRequired, ConfigQr.decode(qr))
        assertEquals(ConfigQrResult.WrongPin, ConfigQr.decode(qr, "0001"))
        assertEquals("tok", assertIs<ConfigQrResult.Ok>(ConfigQr.decode(qr, "0000")).payload.secret)
    }

    @Test
    fun `secret interdit en clair`() {
        assertFailsWith<IllegalArgumentException> { ConfigQr.encode(config, secret = "tok") }
        val forged = "KURA1:" + Base64Codec.encodeUrl("""{"config":{},"secret":"tok"}""".toByteArray())
        val r = assertIs<ConfigQrResult.Invalid>(ConfigQr.decode(forged))
        assertTrue(r.reason.contains("secret en clair"))
    }

    @Test
    fun `remplissage tolere, contenus invalides`() {
        assertIs<ConfigQrResult.Ok>(ConfigQr.decode(vectorPlainQr + "=="))
        assertIs<ConfigQrResult.Invalid>(ConfigQr.decode("REF-0042"))
        assertIs<ConfigQrResult.Invalid>(ConfigQr.decode("KURA1:@@@"))
        assertIs<ConfigQrResult.Invalid>(ConfigQr.decode("KURA1:" + Base64Codec.encodeUrl("[1]".toByteArray())))
        assertIs<ConfigQrResult.Invalid>(ConfigQr.decode("KURA1:" + Base64Codec.encodeUrl("""{"secret":null}""".toByteArray())))
        assertIs<ConfigQrResult.Invalid>(ConfigQr.decode("KURA1E:AAAA", "1234"))
        assertTrue(ConfigQr.isConfigQr("  KURA1:abc"))
    }
}
