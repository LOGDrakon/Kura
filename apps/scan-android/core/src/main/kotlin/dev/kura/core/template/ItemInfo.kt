package dev.kura.core.template

import dev.kura.core.config.KuraConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Fiche d'un article, normalisée (contrat Kura + `response_mapping`). */
@Serializable
data class ItemInfo(
    val ref: String,
    /** Champs affichables, en texte. Contient `name`, `location`, `quantity`, `unit`… selon le serveur. */
    val fields: Map<String, String>,
    val quantity: Long? = null,
    val imageUrl: String? = null,
) {
    val name: String? get() = fields["name"]
}

/** Champ prêt à afficher. [label] est `null` quand la config ne fournit pas de libellé (l'appli choisit alors le sien). */
data class DisplayField(val key: String, val label: String?, val value: String)

object ItemMapper {
    private val HIDDEN_BY_DEFAULT = setOf("ref", "image_url")

    /**
     * Construit la fiche à partir de la réponse de `get_item`.
     * Les clés de premier niveau sont reprises telles quelles, puis `response_mapping` les complète ou les remplace.
     */
    fun fromResponse(ref: String, body: JsonElement, responseMapping: Map<String, String>?): ItemInfo {
        val values = LinkedHashMap<String, JsonElement>()
        if (body is JsonObject) {
            for ((k, v) in body) if (v is JsonPrimitive && v !is JsonNull) values[k] = v
        }
        responseMapping?.forEach { (key, path) ->
            val v = JsonPath.read(body, path)
            if (v is JsonPrimitive) values[key] = v else if (v != null) values[key] = JsonPrimitive(v.toString())
        }
        val fields = values.mapValues { (_, v) -> (v as JsonPrimitive).content }
        val quantity = (values["quantity"] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }
        return ItemInfo(
            ref = fields["ref"]?.takeIf { it.isNotEmpty() } ?: ref,
            fields = fields,
            quantity = quantity,
            imageUrl = fields["image_url"],
        )
    }

    /** Fiche construite à partir des seuls champs du code (formats `delimited` / `json`, ou hors-ligne). */
    fun fromScan(ref: String, scanned: Map<String, String>): ItemInfo =
        ItemInfo(ref = ref, fields = scanned, quantity = scanned["quantity"]?.toLongOrNull())

    /** Fusionne la fiche serveur avec les champs scannés (le serveur l'emporte). */
    fun merge(scanned: ItemInfo, server: ItemInfo): ItemInfo =
        server.copy(fields = scanned.fields + server.fields)

    /** Champs à afficher, dans l'ordre de `display.show_fields` (ou tous les champs utiles par défaut). */
    fun displayFields(item: ItemInfo, config: KuraConfig): List<DisplayField> {
        val keys = config.display.showFields ?: item.fields.keys.filter { it !in HIDDEN_BY_DEFAULT }
        return keys.mapNotNull { key ->
            val value = item.fields[key] ?: if (key == "quantity") item.quantity?.toString() else null
            value?.let { DisplayField(key, config.display.fieldLabels[key], it) }
        }
    }
}
