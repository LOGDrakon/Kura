package dev.kura.core.template

import dev.kura.core.TestSupport
import dev.kura.core.config.ConfigParseResult
import dev.kura.core.config.KuraConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TemplatesTest {
    @Test
    fun `chemin - segments encodes`() {
        assertEquals("/items/REF-0042", Templates.expandPath("/items/{ref}", mapOf("ref" to "REF-0042")))
        assertEquals("/items/A%2FB%20%C3%A9%3F%23", Templates.expandPath("/items/{ref}", mapOf("ref" to "A/B é?#")))
        assertEquals("/s/x/1~_.-", Templates.expandPath("/s/{a}/{b}", mapOf("a" to "x", "b" to "1~_.-")))
        val e = assertFailsWith<TemplateException> { Templates.expandPath("/items/{sku}", mapOf("ref" to "x")) }
        assertEquals("variable {sku} sans valeur dans « /items/{sku} »", e.message)
    }

    @Test
    fun `variables d'un gabarit`() {
        assertEquals(listOf("a", "b_2"), Templates.variablesOf("{a}-{b_2}-{a}-{9x}-{}"))
    }

    @Test
    fun `corps - types preserves pour une variable seule`() {
        val vars = mapOf("value" to JsonPrimitive(-3), "item_id" to JsonPrimitive("REF-1"), "op_id" to JsonPrimitive("u"))
        val body = Templates.renderBody(
            linkedMapOf(
                "quantite" to "{value}",
                "article" to "{item_id}",
                "libelle" to "Mouvement {value} sur {item_id}",
                "ref_ext" to "op-{op_id}-{user}",
                "user" to "{user}",
                "fixe" to "kura",
            ),
            vars,
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"quantite":-3,"article":"REF-1","libelle":"Mouvement -3 sur REF-1","ref_ext":"op-u-","fixe":"kura"}""",
            ),
            body,
        )
    }

    @Test
    fun `chemin pointe`() {
        val json = Json.parseToJsonElement("""{"data":{"stock":{"disponible":12},"items":[{"n":"a"},{"n":"b"}],"vide":null}}""")
        assertEquals(JsonPrimitive(12), JsonPath.read(json, "data.stock.disponible"))
        assertEquals(JsonPrimitive("b"), JsonPath.read(json, "data.items.1.n"))
        assertNull(JsonPath.read(json, "data.items.5.n"))
        assertNull(JsonPath.read(json, "data.stock.x"))
        assertNull(JsonPath.read(json, "data.vide"))
        assertNull(JsonPath.read(json, "data.stock.disponible.x"))
        assertEquals(json, JsonPath.read(json, ""))
    }

    @Test
    fun `fiche - contrat Kura`() {
        val body = Json.parseToJsonElement(
            """{"ref":"REF-0042","name":"Vis M6","location":"A12","quantity":250,"unit":"pcs","image_url":"/items/REF-0042/image","tags":["x"]}""",
        )
        val item = ItemMapper.fromResponse("REF-0042", body, null)
        assertEquals(250, item.quantity)
        assertEquals("Vis M6", item.name)
        assertEquals("/items/REF-0042/image", item.imageUrl)
        val cfg = (KuraConfig.parse(TestSupport.specFile("examples/kura-config.minimal.json").readText()) as ConfigParseResult.Valid).config
        val shown = ItemMapper.displayFields(item, cfg)
        assertEquals(listOf("name", "location", "quantity", "unit"), shown.map { it.key })
        assertNull(shown[0].label)
    }

    @Test
    fun `fiche - response_mapping et libelles de l'exemple complet`() {
        val cfg = (KuraConfig.parse(TestSupport.specFile("examples/kura-config.full.json").readText()) as ConfigParseResult.Valid).config
        val body = Json.parseToJsonElement("""{"designation":"Courroie SPZ","emplacement":"B04","stock":{"disponible":6}}""")
        val item = ItemMapper.fromResponse("REF-0100", body, cfg.responseMapping)
        assertEquals("REF-0100", item.ref)
        assertEquals(6, item.quantity)
        val shown = ItemMapper.displayFields(item, cfg)
        assertEquals(
            listOf(
                DisplayField("name", "Désignation", "Courroie SPZ"),
                DisplayField("location", "Emplacement", "B04"),
                DisplayField("quantity", "Stock", "6"),
            ),
            shown,
        )
    }

    @Test
    fun `fiche depuis le code et fusion`() {
        val scanned = ItemMapper.fromScan("R", mapOf("ref" to "R", "name" to "Vis", "lot" to "L1"))
        val server = ItemInfo("R", mapOf("ref" to "R", "name" to "Vis inox", "quantity" to "3"), 3)
        val merged = ItemMapper.merge(scanned, server)
        assertEquals(mapOf("ref" to "R", "name" to "Vis inox", "lot" to "L1", "quantity" to "3"), merged.fields)
        assertEquals(3, merged.quantity)
        assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement("{}"))
    }
}
