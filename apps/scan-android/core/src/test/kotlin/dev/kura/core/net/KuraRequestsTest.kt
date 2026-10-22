package dev.kura.core.net

import dev.kura.core.TestSupport
import dev.kura.core.config.AuthConfig
import dev.kura.core.config.AuthType
import dev.kura.core.config.ConfigParseResult
import dev.kura.core.config.DisplayConfig
import dev.kura.core.config.KuraConfig
import dev.kura.core.config.ServerConfig
import dev.kura.core.ops.OpType
import dev.kura.core.ops.Operation
import dev.kura.core.template.ItemInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KuraRequestsTest {
    private fun cfg(auth: AuthConfig = AuthConfig(), base: String = "https://gmao.example.fr/api/") =
        KuraConfig(server = ServerConfig(base, auth, timeoutMs = 5000))

    private val op = Operation("4b2f8d4e-0a7c-4a53-9d55-2f1e9b0c6a11", "REF 42/A", OpType.DELTA, -2, "2026-10-22T13:55:12Z", "dev", null, "s1")

    @Test
    fun `contrat Kura par defaut`() {
        val r = KuraRequests(cfg(), { null })
        val get = r.getItem(mapOf("ref" to "REF 42/A"))
        assertEquals("GET", get.method)
        assertEquals("https://gmao.example.fr/api/items/REF%2042%2FA", get.url)
        assertNull(get.body)
        assertEquals(5000, get.timeoutMs)
        assertEquals("application/json", get.headers["Accept"])
        assertFalse("Content-Type" in get.headers)

        val send = r.sendOperation(OutgoingOperation(op))
        assertEquals("POST", send.method)
        assertEquals("https://gmao.example.fr/api/operations", send.url)
        assertEquals("application/json; charset=utf-8", send.headers["Content-Type"])
        assertEquals(
            Json.parseToJsonElement(
                """{"op_id":"4b2f8d4e-0a7c-4a53-9d55-2f1e9b0c6a11","item_id":"REF 42/A","type":"delta","value":-2,"client_ts":"2026-10-22T13:55:12Z","device_id":"dev","session_id":"s1"}""",
            ),
            Json.parseToJsonElement(send.body!!),
        )
        val batch = r.sendBatch(listOf(OutgoingOperation(op), OutgoingOperation(op.copy(opId = "x"))))
        assertEquals("https://gmao.example.fr/api/operations/batch", batch.url)
        assertEquals(2, Json.parseToJsonElement(batch.body!!).let { (it as kotlinx.serialization.json.JsonObject)["operations"] as kotlinx.serialization.json.JsonArray }.size)
        assertEquals("https://gmao.example.fr/api/config", r.getConfig().url)
    }

    @Test
    fun `authentification`() {
        assertEquals(mapOf("Authorization" to "Bearer t0k"), KuraRequests(cfg(AuthConfig(AuthType.BEARER)), { "t0k" }).authHeaders())
        assertEquals(
            mapOf("Authorization" to "Basic amR1cG9udDpww6lyw6k="),
            KuraRequests(cfg(AuthConfig(AuthType.BASIC, username = "jdupont")), { "péré" }).authHeaders(),
        )
        assertEquals(mapOf("X-Api-Key" to "k"), KuraRequests(cfg(AuthConfig(AuthType.API_KEY)), { "k" }).authHeaders())
        assertEquals(mapOf("X-Token" to "k"), KuraRequests(cfg(AuthConfig(AuthType.API_KEY, header = "X-Token")), { "k" }).authHeaders())
        assertEquals(emptyMap(), KuraRequests(cfg(AuthConfig(AuthType.NONE)), { "k" }).authHeaders())
        val missing = KuraRequests(cfg(AuthConfig(AuthType.BEARER)), { null })
        assertEquals(emptyMap(), missing.authHeaders())
        assertTrue(missing.isMissingSecret)
        val get = KuraRequests(cfg(AuthConfig(AuthType.BEARER)), { "t" }).getItem(mapOf("ref" to "a"))
        assertEquals("Bearer t", get.headers["Authorization"])
        assertFalse(get.toString().contains("Bearer"))
    }

    @Test
    fun `mapping de l'exemple complet`() {
        val c = (KuraConfig.parse(TestSupport.specFile("examples/kura-config.full.json").readText()) as ConfigParseResult.Valid).config
        val r = KuraRequests(c, { "tok" })
        val send = r.sendOperation(OutgoingOperation(op.copy(itemId = "REF-0042"), mapOf("name" to "Vis", "item_id" to "ignoré")))
        assertEquals("https://gmao.entreprise.fr/api/stock/movements", send.url)
        assertEquals(
            Json.parseToJsonElement(
                """{"article":"REF-0042","quantite":-2,"mode":"delta","horodatage":"2026-10-22T13:55:12Z","id_unique":"4b2f8d4e-0a7c-4a53-9d55-2f1e9b0c6a11"}""",
            ),
            Json.parseToJsonElement(send.body!!),
        )
        assertEquals("https://gmao.entreprise.fr/api/items/REF-0042", r.getItem(mapOf("ref" to "REF-0042", "name" to "Vis")).url)
        assertEquals("https://gmao.entreprise.fr/api/kura/config", r.getConfig().url)
        // Image : URL du serveur prioritaire, sinon gabarit display.image_url.
        assertEquals("https://gmao.entreprise.fr/api/items/REF-0042/image", r.imageUrl(null, mapOf("ref" to "REF-0042")))
        assertEquals("https://cdn.example.com/x.png", r.imageUrl(ItemInfo("R", emptyMap(), imageUrl = "https://cdn.example.com/x.png"), mapOf("ref" to "R")))
    }

    @Test
    fun `images et origine`() {
        val r = KuraRequests(cfg(base = "https://gmao.example.fr:443/api"), { null })
        assertNull(r.imageUrl(ItemInfo("R", emptyMap(), imageUrl = "/i.png"), emptyMap())) // show_image faux
        val shown = KuraRequests(cfg().copy(display = DisplayConfig(showImage = true, imageUrl = "/img/{sku}")), { null })
        assertNull(shown.imageUrl(null, mapOf("ref" to "R"))) // variable inconnue → pas d'image
        assertEquals("https://gmao.example.fr/api/i.png", shown.imageUrl(ItemInfo("R", emptyMap(), imageUrl = "/i.png"), emptyMap()))
        assertTrue(r.isSameOrigin("https://gmao.example.fr/autre/chemin"))
        assertFalse(r.isSameOrigin("https://cdn.example.fr/x"))
        assertFalse(r.isSameOrigin("http://gmao.example.fr/x"))
        assertFalse(r.isSameOrigin("pas une url"))
    }

    @Test
    fun `get_item en POST envoie les champs scannes`() {
        val c = cfg().copy(
            endpoints = dev.kura.core.config.EndpointsConfig(
                getItem = dev.kura.core.config.Endpoint(dev.kura.core.config.HttpMethod.POST, "/search"),
            ),
        )
        val req = KuraRequests(c, { null }).getItem(mapOf("ref" to "R", "lot" to "L"))
        assertEquals("POST", req.method)
        assertEquals(Json.parseToJsonElement("""{"ref":"R","lot":"L"}"""), Json.parseToJsonElement(req.body!!))
    }
}
