package dev.kura.core.template

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets

class TemplateException(message: String) : IllegalArgumentException(message)

/** Gabarits `{variable}` des chemins d'endpoint et de `request_mapping`. */
object Templates {
    private val VAR = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")

    fun variablesOf(template: String): List<String> = VAR.findAll(template).map { it.groupValues[1] }.distinct().toList()

    /**
     * Remplit un chemin d'URL (`/items/{ref}`) : chaque valeur est encodée comme segment de chemin
     * (RFC 3986, `/` et espaces encodés). Une variable absente est une erreur.
     */
    fun expandPath(template: String, vars: Map<String, String>): String =
        VAR.replace(template) { m ->
            val name = m.groupValues[1]
            val value = vars[name] ?: throw TemplateException("variable {$name} sans valeur dans « $template »")
            encodePathSegment(value)
        }

    /**
     * Construit le corps JSON d'une opération à partir de `request_mapping`.
     * - gabarit réduit à une seule variable (`"{value}"`) : la valeur garde son type JSON (nombre reste nombre) ;
     *   si la variable est absente, la clé est omise ;
     * - gabarit composé (`"op-{op_id}"`) : texte, variables absentes remplacées par une chaîne vide.
     */
    fun renderBody(mapping: Map<String, String>, vars: Map<String, JsonPrimitive>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, template) in mapping) {
            val whole = VAR.matchEntire(template)
            if (whole != null) {
                vars[whole.groupValues[1]]?.let { out[key] = it }
            } else {
                out[key] = JsonPrimitive(VAR.replace(template) { m -> vars[m.groupValues[1]]?.content ?: "" })
            }
        }
        return JsonObject(out)
    }

    fun encodePathSegment(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
