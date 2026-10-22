package dev.kura.core.template

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Lecture d'un chemin pointé (`stock.disponible`, `data.items.0.name`) dans une réponse JSON. */
object JsonPath {
    fun read(root: JsonElement, path: String): JsonElement? {
        if (path.isEmpty()) return root
        var current: JsonElement = root
        for (segment in path.split('.')) {
            current = when (current) {
                is JsonObject -> current[segment]
                is JsonArray -> segment.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return if (current is JsonNull) null else current
    }
}
