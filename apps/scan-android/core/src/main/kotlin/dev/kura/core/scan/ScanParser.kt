package dev.kura.core.scan

import dev.kura.core.config.CodeFormat
import dev.kura.core.config.ScanConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Code lu et décomposé. [fields] contient toujours l'identifiant sous `id_field` et sous `ref`. */
data class ScannedCode(
    val itemId: String,
    val fields: Map<String, String>,
    val raw: String,
)

sealed interface ParseOutcome {
    data class Ok(val code: ScannedCode) : ParseOutcome
    data class Rejected(val reason: String) : ParseOutcome
}

/** Analyse le contenu d'un code selon `scan.format` (ADR 0003). */
class ScanParser(private val config: ScanConfig) {

    fun parse(raw: String): ParseOutcome {
        var text = raw.trim { it.isWhitespace() || it.isISOControl() }
        val prefix = config.prefix
        if (!prefix.isNullOrEmpty()) {
            if (!text.startsWith(prefix)) return ParseOutcome.Rejected("Code non reconnu : préfixe « $prefix » attendu.")
            text = text.removePrefix(prefix).trim()
        }
        if (text.isEmpty()) return ParseOutcome.Rejected("Code vide.")
        return when (config.format) {
            CodeFormat.ID -> ok(text, emptyMap(), raw)
            CodeFormat.DELIMITED -> parseDelimited(text, raw)
            CodeFormat.JSON -> parseJson(text, raw)
        }
    }

    private fun parseDelimited(text: String, raw: String): ParseOutcome {
        val parts = text.split(config.delimiter).map { it.trim() }
        val fields = LinkedHashMap<String, String>()
        config.fields.forEachIndexed { i, name -> parts.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { fields[name] = it } }
        val id = fields[config.idField]
            ?: return ParseOutcome.Rejected("Code incomplet : champ « ${config.idField} » absent.")
        return ok(id, fields, raw)
    }

    private fun parseJson(text: String, raw: String): ParseOutcome {
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return ParseOutcome.Rejected("Code illisible : JSON attendu.")
        val fields = LinkedHashMap<String, String>()
        for (name in config.fields) {
            val v = obj[name] ?: continue
            if (v is JsonNull) continue
            val s = if (v is JsonPrimitive) v.content else v.toString()
            if (s.isNotEmpty()) fields[name] = s.trim()
        }
        val id = fields[config.idField]
            ?: return ParseOutcome.Rejected("Code incomplet : champ « ${config.idField} » absent.")
        return ok(id, fields, raw)
    }

    private fun ok(id: String, fields: Map<String, String>, raw: String): ParseOutcome {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return ParseOutcome.Rejected("Code vide.")
        if (trimmed.length > MAX_ID_LENGTH) return ParseOutcome.Rejected("Identifiant trop long (128 caractères max).")
        val all = LinkedHashMap(fields)
        all[config.idField] = trimmed
        all.putIfAbsent("ref", trimmed)
        return ParseOutcome.Ok(ScannedCode(trimmed, all, raw))
    }

    companion object {
        const val MAX_ID_LENGTH = 128
    }
}
