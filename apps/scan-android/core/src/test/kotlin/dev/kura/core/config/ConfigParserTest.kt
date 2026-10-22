package dev.kura.core.config

import dev.kura.core.TestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigParserTest {
    private fun invalid(json: String): ConfigParseResult.Invalid = assertIs(KuraConfig.parse(json))
    private fun valid(json: String): ConfigParseResult.Valid = assertIs(KuraConfig.parse(json))

    private val base = """"kura_config_version": 1, "server": { "base_url": "https://gmao.example.fr/api" }"""

    @Test
    fun `exemple minimal valide avec avertissement HTTP`() {
        val r = valid(TestSupport.specFile("examples/kura-config.minimal.json").readText())
        assertEquals("http://10.0.2.2:8787", r.config.server.baseUrl)
        assertEquals(CodeFormat.ID, r.config.scan.format)
        assertEquals(ScanConfig.DEFAULT_SYMBOLOGIES, r.config.scan.symbologies)
        assertTrue(r.config.isCleartext)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings[0].startsWith("server.base_url : connexion non chiffrée"))
    }

    @Test
    fun `exemple complet valide`() {
        val r = valid(TestSupport.specFile("examples/kura-config.full.json").readText())
        val c = r.config
        assertEquals("Atelier Dupont", c.company)
        assertEquals(AuthType.BEARER, c.server.auth.type)
        assertEquals(CodeFormat.DELIMITED, c.scan.format)
        assertEquals(listOf("ref", "name", "location"), c.scan.fields)
        assertEquals(HttpMethod.POST, c.effectiveEndpoints.sendOp.method)
        assertEquals("/stock/movements", c.effectiveEndpoints.sendOp.path)
        assertEquals("/operations/batch", c.effectiveEndpoints.sendBatch.path)
        assertEquals("{value}", c.requestMapping!!["quantite"])
        assertEquals("stock.disponible", c.responseMapping!!["quantity"])
        assertEquals(listOf(1, 10), c.display.stepButtons)
        assertEquals(Direction.OUT, c.burst.defaultDirection)
        assertFalse(c.usesKuraContract)
        assertFalse(c.usesBatch)
        assertTrue(c.security.configLocked)
        assertEquals(emptyList(), r.warnings)
    }

    @Test
    fun `valeurs par defaut du schema`() {
        val c = valid("{ $base, \"scan\": {} }").config
        assertEquals(8000, c.server.timeoutMs)
        assertEquals(AuthType.NONE, c.server.auth.type)
        assertEquals("X-Api-Key", c.server.auth.header)
        assertEquals(";", c.scan.delimiter)
        assertEquals("ref", c.scan.idField)
        assertEquals(TriggerMode.BUTTON, c.scan.trigger.mode)
        assertEquals(6000, c.scan.trigger.timeoutMs)
        assertEquals(0.6, c.scan.trigger.aimArea)
        assertTrue(c.scan.trigger.volumeKeys)
        assertEquals(listOf(1), c.display.stepButtons)
        assertTrue(c.display.allowSet)
        assertFalse(c.display.showImage)
        assertTrue(c.burst.enabled)
        assertEquals(1500, c.burst.sameCodeCooldownMs)
        assertEquals(SyncMode.AUTO, c.sync.mode)
        assertTrue(c.sync.batch)
        assertEquals(50, c.sync.maxBatchSize)
        assertEquals(listOf(5, 30, 120), c.sync.retryBackoffS)
        assertTrue(c.usesBatch)
        assertEquals("/items/{ref}", c.effectiveEndpoints.getItem.path)
        assertEquals(emptyList(), valid("{ $base, \"scan\": {} }").warnings)
    }

    @Test
    fun `format delimite sans fields refuse en francais`() {
        val r = invalid("{ $base, \"scan\": { \"format\": \"delimited\" } }")
        assertEquals(listOf("scan : le format délimité exige la liste « fields » (noms des champs du code)."), r.errors)
    }

    @Test
    fun `id_field absent vaut ref par defaut`() {
        valid("{ $base, \"scan\": { \"format\": \"delimited\", \"fields\": [\"ref\", \"nom\"] } }")
        val r = invalid("{ $base, \"scan\": { \"format\": \"delimited\", \"fields\": [\"code\", \"nom\"] } }")
        assertEquals(listOf("scan.id_field : « ref » ne fait pas partie de « fields » (code, nom)."), r.errors)
    }

    @Test
    fun `format json avec id_field absent de fields`() {
        val r = invalid("{ $base, \"scan\": { \"format\": \"json\", \"fields\": [\"code\", \"nom\"], \"id_field\": \"ref\" } }")
        assertEquals(listOf("scan.id_field : « ref » ne fait pas partie de « fields » (code, nom)."), r.errors)
    }

    @Test
    fun `champs obligatoires`() {
        val r = invalid("{ \"kura_config_version\": 1 }")
        assertEquals(listOf("server : obligatoire.", "scan : obligatoire."), r.errors)
    }

    @Test
    fun `JSON illisible`() {
        assertEquals(listOf("Le fichier n'est pas un JSON valide."), invalid("{ pas du json").errors)
        assertEquals(listOf("la config doit être un objet JSON."), invalid("[1,2]").errors)
    }

    @Test
    fun `version de config inconnue`() {
        val r = invalid("{ \"kura_config_version\": 2, \"server\": { \"base_url\": \"https://a.fr\" }, \"scan\": {} }")
        assertEquals(listOf("kura_config_version : version de config non prise en charge (attendu : 1)."), r.errors)
    }

    @Test
    fun `propriete inconnue, enum et bornes`() {
        val r = invalid(
            """{ $base, "scan": { "format": "barcode", "symbologies": ["qr_code", "pdf417"], "trigger": { "aim_area": 0.1 } },
               "sync": { "max_batch_size": 0, "retry_backoff_s": [] }, "colour": "red" }""",
        )
        assertTrue("scan.format : valeur « barcode » non reconnue (attendu : id, delimited, json)." in r.errors, r.errors.toString())
        assertTrue(r.errors.any { it.startsWith("scan.symbologies[1] : valeur « pdf417 » non reconnue") })
        assertTrue("scan.trigger.aim_area : valeur 0.1 hors limites (entre 0.2 et 1.0)." in r.errors)
        assertTrue("sync.max_batch_size : valeur 0 hors limites (entre 1 et 500)." in r.errors)
        assertTrue("sync.retry_backoff_s : doit contenir au moins 1 élément(s)." in r.errors)
        assertTrue("colour : propriété inconnue." in r.errors)
    }

    @Test
    fun `adresse serveur`() {
        val r = invalid("{ \"kura_config_version\": 1, \"server\": { \"base_url\": \"ftp://x\" }, \"scan\": {} }")
        assertEquals(listOf("server.base_url : doit commencer par https:// (ou http:// en test local)."), r.errors)
        val r2 = invalid("{ \"kura_config_version\": 1, \"server\": { \"base_url\": \"https://\" }, \"scan\": {} }")
        assertEquals(listOf("server.base_url : adresse invalide « https:// »."), r2.errors)
    }

    @Test
    fun `types incorrects`() {
        val r = invalid("""{ $base, "scan": { "fields": "ref" }, "display": { "show_image": "oui", "step_buttons": [1, 1.5] } }""")
        assertTrue("scan.fields : doit être une liste." in r.errors)
        assertTrue("display.show_image : doit valoir true ou false." in r.errors)
        assertTrue("display.step_buttons[1] : doit être un nombre entier." in r.errors)
    }

    @Test
    fun `nom de champ invalide`() {
        val r = invalid("""{ $base, "scan": { "format": "delimited", "fields": ["Ref"], "id_field": "Ref" } }""")
        assertTrue(r.errors.first().startsWith("scan.fields[0] : nom de champ « Ref » invalide"))
    }

    @Test
    fun `mot de passe en clair refuse`() {
        val r = invalid("""{ $base, "scan": {}, "security": { "config_locked": true, "config_password_hash": "secret123" } }""")
        assertTrue(r.errors.single().startsWith("security.config_password_hash : doit être un hash argon2id"))
        valid("""{ $base, "scan": {}, "security": { "config_password_hash": "argon2id${'$'}v=19${'$'}m=8,t=1,p=1${'$'}YQ${'$'}YQ" } }""")
    }

    @Test
    fun `avertissements de coherence`() {
        val r = valid(
            """{ $base, "scan": {}, "request_mapping": { "article": "{item_id}", "lot": "{batch_no}" },
               "endpoints": { "get_item": { "method": "GET", "path": "/articles/{sku}" } },
               "security": { "config_locked": true }, "remote_config": { "enabled": true } }""",
        )
        assertEquals(
            listOf(
                "endpoints.get_item.path : variable(s) inconnue(s) : {sku}.",
                "request_mapping.lot : variable(s) inconnue(s) : {batch_no}.",
                "security : config verrouillée sans hash de mot de passe : le mot de passe sera défini sur l'appareil.",
                "remote_config : config distante activée sans « signing_public_key » : toute config distante sera refusée.",
            ),
            r.warnings,
        )
    }

    @Test
    fun `champs scannes disponibles dans les gabarits`() {
        val r = valid(
            """{ $base, "scan": { "format": "delimited", "fields": ["code", "lot"], "id_field": "code" },
               "request_mapping": { "article": "{code}", "lot": "{lot}", "q": "{value}" } }""",
        )
        assertEquals(emptyList(), r.warnings)
    }

    @Test
    fun `schema dollar toléré`() {
        assertNull(KuraConfig.parse("""{ "${'$'}schema": "x", $base, "scan": {} }""").let { (it as? ConfigParseResult.Invalid) })
    }

    @Test
    fun `envoi par lot avec request_mapping seulement si send_batch explicite`() {
        val c = valid(
            """{ $base, "scan": {}, "request_mapping": { "a": "{item_id}" },
               "endpoints": { "send_batch": { "method": "POST", "path": "/bulk" } } }""",
        ).config
        assertTrue(c.usesBatch)
    }
}
