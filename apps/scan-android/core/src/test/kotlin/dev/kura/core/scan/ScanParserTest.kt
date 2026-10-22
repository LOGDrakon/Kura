package dev.kura.core.scan

import dev.kura.core.config.CodeFormat
import dev.kura.core.config.ScanConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScanParserTest {
    private fun ok(cfg: ScanConfig, raw: String) = assertIs<ParseOutcome.Ok>(ScanParser(cfg).parse(raw)).code
    private fun rejected(cfg: ScanConfig, raw: String) = assertIs<ParseOutcome.Rejected>(ScanParser(cfg).parse(raw)).reason

    @Test
    fun `format id - identifiant seul, espaces et retours retires`() {
        val c = ok(ScanConfig(), "  REF-0042\r\n")
        assertEquals("REF-0042", c.itemId)
        assertEquals(mapOf("ref" to "REF-0042"), c.fields)
        assertEquals("  REF-0042\r\n", c.raw)
    }

    @Test
    fun `format id avec id_field personnalise`() {
        val c = ok(ScanConfig(idField = "code"), "3760123456789")
        assertEquals(mapOf("code" to "3760123456789", "ref" to "3760123456789"), c.fields)
    }

    @Test
    fun `prefixe retire ou code refuse`() {
        val cfg = ScanConfig(prefix = "KURA:")
        assertEquals("REF-1", ok(cfg, "KURA:REF-1").itemId)
        assertEquals("Code non reconnu : préfixe « KURA: » attendu.", rejected(cfg, "REF-1"))
        assertEquals("Code vide.", rejected(cfg, "KURA:  "))
    }

    @Test
    fun `format delimite`() {
        val cfg = ScanConfig(format = CodeFormat.DELIMITED, fields = listOf("ref", "name", "location"))
        val c = ok(cfg, "REF-0042; Vis M6 ;A12")
        assertEquals("REF-0042", c.itemId)
        assertEquals(mapOf("ref" to "REF-0042", "name" to "Vis M6", "location" to "A12"), c.fields)
        // Champs manquants tolérés, champs en trop ignorés.
        assertEquals(mapOf("ref" to "R"), ok(cfg, "R").fields)
        assertEquals(3, ok(cfg, "R;a;b;c;d").fields.size)
    }

    @Test
    fun `format delimite - identifiant ailleurs et separateur multi-caracteres`() {
        val cfg = ScanConfig(format = CodeFormat.DELIMITED, delimiter = "||", fields = listOf("name", "code"), idField = "code")
        val c = ok(cfg, "Écrou M6||REF-0043")
        assertEquals("REF-0043", c.itemId)
        assertEquals("REF-0043", c.fields["ref"])
        assertEquals("Code incomplet : champ « code » absent.", rejected(cfg, "Écrou M6"))
    }

    @Test
    fun `format json`() {
        val cfg = ScanConfig(format = CodeFormat.JSON, fields = listOf("ref", "name", "qty"))
        val c = ok(cfg, """{"ref":"REF-1","name":"Vis","qty":12,"autre":"x"}""")
        assertEquals(mapOf("ref" to "REF-1", "name" to "Vis", "qty" to "12"), c.fields)
        assertEquals("Code illisible : JSON attendu.", rejected(cfg, "REF-1"))
        assertEquals("Code illisible : JSON attendu.", rejected(cfg, "[1]"))
        assertEquals("Code incomplet : champ « ref » absent.", rejected(cfg, """{"name":"Vis"}"""))
        assertEquals("Code incomplet : champ « ref » absent.", rejected(cfg, """{"ref":null}"""))
    }

    @Test
    fun `identifiant trop long`() {
        assertEquals("Identifiant trop long (128 caractères max).", rejected(ScanConfig(), "x".repeat(129)))
        ok(ScanConfig(), "x".repeat(128))
    }

    @Test
    fun `symbologies - noms du schema`() {
        assertEquals(Symbology.EAN_13, Symbology.fromSchemaName("ean_13"))
        assertEquals(null, Symbology.fromSchemaName("pdf417"))
        assertEquals(Symbology.DEFAULT.toSet(), Symbology.fromSchemaNames(ScanConfig.DEFAULT_SYMBOLOGIES))
        assertEquals(Symbology.DEFAULT.toSet(), Symbology.fromSchemaNames(emptyList()))
        assertEquals(setOf(Symbology.ITF), Symbology.fromSchemaNames(listOf("itf", "inconnu")))
        assertEquals(9, Symbology.entries.size)
        Symbology.entries.forEach { assertEquals(it, Symbology.fromSchemaName(it.schemaName)) }
    }
}
