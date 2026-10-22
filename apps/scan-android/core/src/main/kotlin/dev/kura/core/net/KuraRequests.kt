package dev.kura.core.net

import dev.kura.core.config.AuthType
import dev.kura.core.config.Endpoint
import dev.kura.core.config.HttpMethod
import dev.kura.core.config.KuraConfig
import dev.kura.core.ops.Operation
import dev.kura.core.template.ItemInfo
import dev.kura.core.template.Templates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.Base64

/** Opération en attente d'envoi, avec les champs scannés (variables supplémentaires de `request_mapping`). */
data class OutgoingOperation(val op: Operation, val extras: Map<String, String> = emptyMap())

/**
 * Construction des requêtes vers le serveur de l'entreprise : URL, méthode, en-têtes d'authentification, corps JSON.
 * Aucune E/S ici : testable sur JVM.
 *
 * @param secret secret courant (jeton, mot de passe basic ou clé API), lu dans le stockage sécurisé.
 */
class KuraRequests(
    val config: KuraConfig,
    private val secret: () -> String?,
    private val userAgent: String = "KuraScan",
) {
    private val endpoints = config.effectiveEndpoints
    val baseUrl: String = config.server.baseUrl.trimEnd('/')

    fun url(path: String): String = baseUrl + (if (path.startsWith("/")) path else "/$path")

    /** En-têtes d'authentification selon `server.auth`. Vide si aucun secret n'est disponible. */
    fun authHeaders(): Map<String, String> {
        val auth = config.server.auth
        val s = secret()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return when (auth.type) {
            AuthType.NONE -> emptyMap()
            AuthType.BEARER -> mapOf("Authorization" to "Bearer $s")
            AuthType.BASIC -> {
                val raw = "${auth.username.orEmpty()}:$s".toByteArray(Charsets.UTF_8)
                mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString(raw))
            }
            AuthType.API_KEY -> mapOf(auth.header to s)
        }
    }

    /** `true` si l'authentification est configurée mais qu'aucun secret n'a été saisi. */
    val isMissingSecret: Boolean
        get() = config.server.auth.type != AuthType.NONE && secret().isNullOrEmpty()

    private fun headers(withBody: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("User-Agent", userAgent)
        if (withBody) put("Content-Type", "application/json; charset=utf-8")
        putAll(authHeaders())
    }

    private fun request(endpoint: Endpoint, vars: Map<String, String>, body: JsonElement?): HttpRequestSpec {
        val withBody = body != null && endpoint.method != HttpMethod.GET
        return HttpRequestSpec(
            method = endpoint.method.name,
            url = url(Templates.expandPath(endpoint.path, vars)),
            headers = headers(withBody),
            body = if (withBody) JSON.encodeToString(JsonElement.serializer(), body!!) else null,
            timeoutMs = config.server.timeoutMs,
        )
    }

    /** `GET /items/{ref}` (ou `endpoints.get_item`). [fields] = champs scannés (contiennent `ref` et `id_field`). */
    fun getItem(fields: Map<String, String>): HttpRequestSpec {
        val body = if (endpoints.getItem.method == HttpMethod.GET) null
        else JsonObject(fields.mapValues { JsonPrimitive(it.value) })
        return request(endpoints.getItem, fields, body)
    }

    fun sendOperation(out: OutgoingOperation): HttpRequestSpec =
        request(endpoints.sendOp, pathVars(out), operationBody(out))

    fun sendBatch(ops: List<OutgoingOperation>): HttpRequestSpec {
        require(ops.isNotEmpty()) { "lot vide" }
        val body = JsonObject(mapOf("operations" to JsonArray(ops.map { operationBody(it) })))
        return request(endpoints.sendBatch, emptyMap(), body)
    }

    fun getConfig(): HttpRequestSpec {
        val endpoint = config.remoteConfig.url?.let { Endpoint(HttpMethod.GET, it) } ?: endpoints.getConfig
        return if (endpoint.path.startsWith("http://") || endpoint.path.startsWith("https://")) {
            HttpRequestSpec("GET", endpoint.path, headers(false), null, config.server.timeoutMs)
        } else {
            request(endpoint, emptyMap(), null)
        }
    }

    /** Corps d'une opération : contrat Kura, ou `request_mapping` si présent. */
    fun operationBody(out: OutgoingOperation): JsonElement {
        val mapping = config.requestMapping
        if (mapping.isNullOrEmpty()) return JSON.encodeToJsonElement(Operation.serializer(), out.op)
        val vars = LinkedHashMap<String, JsonPrimitive>()
        out.extras.forEach { (k, v) -> vars[k] = JsonPrimitive(v) }
        vars.putAll(out.op.variables()) // les champs de l'opération priment sur les champs scannés
        return Templates.renderBody(mapping, vars)
    }

    private fun pathVars(out: OutgoingOperation): Map<String, String> =
        out.extras + out.op.variables().mapValues { it.value.content }

    /**
     * URL de l'image d'un article : `image_url` renvoyé par le serveur, sinon le gabarit `display.image_url`.
     * Les chemins relatifs sont résolus sur `base_url`. `null` si `display.show_image` est faux.
     */
    fun imageUrl(item: ItemInfo?, scanFields: Map<String, String>): String? {
        if (!config.display.showImage) return null
        val candidate = item?.imageUrl ?: config.display.imageUrl?.let { template ->
            val vars = scanFields + (item?.fields ?: emptyMap())
            try {
                Templates.expandPath(template, vars)
            } catch (e: IllegalArgumentException) {
                return null
            }
        } ?: return null
        return resolve(candidate)
    }

    fun resolve(urlOrPath: String): String =
        if (urlOrPath.startsWith("http://", true) || urlOrPath.startsWith("https://", true)) urlOrPath else url(urlOrPath)

    /** Les en-têtes d'authentification ne sont envoyés qu'à l'origine de `base_url` (jamais à un hôte tiers). */
    fun isSameOrigin(url: String): Boolean = try {
        val a = URI(baseUrl)
        val b = URI(url)
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)
    } catch (e: Exception) {
        false
    }

    private fun effectivePort(u: URI): Int = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80

    companion object {
        val JSON = Json {
            explicitNulls = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
