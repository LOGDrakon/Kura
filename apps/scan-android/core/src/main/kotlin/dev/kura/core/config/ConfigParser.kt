package dev.kura.core.config

import dev.kura.core.ops.OperationVariables
import dev.kura.core.template.Templates
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI

/** Résultat de la lecture d'une config. */
sealed interface ConfigParseResult {
    val warnings: List<String>

    data class Valid(val config: KuraConfig, override val warnings: List<String>) : ConfigParseResult

    data class Invalid(val errors: List<String>, override val warnings: List<String>) : ConfigParseResult

    fun getOrNull(): KuraConfig? = (this as? Valid)?.config
}

/**
 * Validation de `kura-config.json` équivalente à `spec/config.schema.json`, avec des messages en français
 * lisibles par un administrateur, plus quelques contrôles de cohérence (ex. `id_field` présent dans `fields`).
 */
object ConfigParser {
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private val FIELD_NAME = Regex("^[a-z][a-z0-9_]{0,39}$")
    private val ARGON2_PHC = Regex("^\\$?argon2id\\$")

    fun parse(text: String): ConfigParseResult {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return ConfigParseResult.Invalid(listOf("Le fichier n'est pas un JSON valide."), emptyList())
        } catch (e: IllegalArgumentException) {
            return ConfigParseResult.Invalid(listOf("Le fichier n'est pas un JSON valide."), emptyList())
        }
        return parse(element)
    }

    fun parse(element: JsonElement): ConfigParseResult {
        val v = Validator()
        v.root(element)
        if (v.errors.isNotEmpty()) return ConfigParseResult.Invalid(v.errors, v.warnings)
        return try {
            ConfigParseResult.Valid(json.decodeFromJsonElement(KuraConfig.serializer(), element), v.warnings)
        } catch (e: SerializationException) {
            ConfigParseResult.Invalid(listOf("Config illisible : ${e.message}"), v.warnings)
        } catch (e: IllegalArgumentException) {
            ConfigParseResult.Invalid(listOf("Config illisible : ${e.message}"), v.warnings)
        }
    }

    private class Validator {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        private fun err(path: String, msg: String) {
            errors += if (path.isEmpty()) msg else "$path : $msg"
        }

        private fun warn(path: String, msg: String) {
            warnings += if (path.isEmpty()) msg else "$path : $msg"
        }

        fun root(el: JsonElement) {
            val o = el as? JsonObject ?: return err("", "la config doit être un objet JSON.")
            // "$schema" est toléré (ajouté par les éditeurs JSON), bien qu'absent du schéma.
            keys(
                "", o,
                setOf(
                    "\$schema", "kura_config_version", "company", "server", "scan", "endpoints",
                    "request_mapping", "response_mapping", "display", "burst", "sync", "security", "remote_config",
                ),
            )
            required("", o, "kura_config_version", "server", "scan")
            o["kura_config_version"]?.let {
                if (intOf(it) != 1L) err("kura_config_version", "version de config non prise en charge (attendu : 1).")
            }
            o["company"]?.let { string("company", it, maxLength = 120) }
            o["server"]?.let { server(it) }
            val scanFields = o["scan"]?.let { scan(it) } ?: emptySet()
            o["endpoints"]?.let { endpoints(it, scanFields) }
            o["request_mapping"]?.let { requestMapping(it, scanFields) }
            o["response_mapping"]?.let { stringMap("response_mapping", it) }
            o["display"]?.let { display(it) }
            o["burst"]?.let { burst(it) }
            o["sync"]?.let { sync(it) }
            o["security"]?.let { security(it) }
            o["remote_config"]?.let { remoteConfig(it) }
        }

        private fun server(el: JsonElement) {
            val o = obj("server", el) ?: return
            keys("server", o, setOf("base_url", "auth", "timeout_ms"))
            required("server", o, "base_url")
            o["base_url"]?.let { url ->
                val s = string("server.base_url", url) ?: return@let
                if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
                    err("server.base_url", "doit commencer par https:// (ou http:// en test local).")
                } else if (!validUrl(s)) {
                    err("server.base_url", "adresse invalide « $s ».")
                } else if (s.startsWith("http://", ignoreCase = true)) {
                    warn(
                        "server.base_url",
                        "connexion non chiffrée (HTTP). À réserver à un réseau local de test ; l'appli demandera confirmation.",
                    )
                }
            }
            o["timeout_ms"]?.let { int("server.timeout_ms", it, 1000, 60000) }
            o["auth"]?.let { auth(it) }
        }

        private fun auth(el: JsonElement) {
            val o = obj("server.auth", el) ?: return
            keys("server.auth", o, setOf("type", "token_ref", "header", "username"))
            required("server.auth", o, "type")
            val type = o["type"]?.let { enum("server.auth.type", it, setOf("none", "bearer", "basic", "api_key")) }
            o["token_ref"]?.let { enum("server.auth.token_ref", it, setOf("secure_store", "mdm")) }
            o["header"]?.let { h ->
                val s = string("server.auth.header", h)
                if (s != null && !Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$").matches(s)) {
                    err("server.auth.header", "nom d'en-tête HTTP invalide « $s ».")
                }
            }
            o["username"]?.let { string("server.auth.username", it) }
            if (type == "basic" && o["username"] == null) {
                warn("server.auth", "authentification basic sans « username » : il faudra le saisir sur l'appareil.")
            }
        }

        /** Retourne les noms de champs connus après un scan (pour vérifier les gabarits). */
        private fun scan(el: JsonElement): Set<String> {
            val o = obj("scan", el) ?: return emptySet()
            keys("scan", o, setOf("symbologies", "format", "delimiter", "fields", "id_field", "prefix", "trigger"))
            o["symbologies"]?.let { s ->
                array("scan.symbologies", s, minItems = 1, unique = true)?.forEachIndexed { i, item ->
                    enum("scan.symbologies[$i]", item, SYMBOLOGY_NAMES)
                }
            }
            val format = o["format"]?.let { enum("scan.format", it, setOf("id", "delimited", "json")) } ?: "id"
            o["delimiter"]?.let { string("scan.delimiter", it, minLength = 1, maxLength = 4) }
            val fields = o["fields"]?.let { f ->
                array("scan.fields", f, unique = true)?.mapIndexedNotNull { i, item -> fieldName("scan.fields[$i]", item) }
            }
            val idField = o["id_field"]?.let { fieldName("scan.id_field", it) }
            o["prefix"]?.let { string("scan.prefix", it) }
            o["trigger"]?.let { trigger(it) }
            if (format == "delimited" || format == "json") {
                val label = if (format == "delimited") "délimité" else "JSON"
                if (o["fields"] == null) err("scan", "le format $label exige la liste « fields » (noms des champs du code).")
                if (fields != null && fields.isEmpty()) err("scan.fields", "la liste ne peut pas être vide pour le format $label.")
                // id_field vaut « ref » par défaut (schéma) ; il doit figurer dans fields.
                val id = idField ?: "ref"
                if (fields != null && fields.isNotEmpty() && id !in fields) {
                    err("scan.id_field", "« $id » ne fait pas partie de « fields » (${fields.joinToString(", ")}).")
                }
            }
            return buildSet {
                add(idField ?: "ref")
                add("ref")
                fields?.let { addAll(it) }
            }
        }

        private fun trigger(el: JsonElement) {
            val o = obj("scan.trigger", el) ?: return
            keys("scan.trigger", o, setOf("mode", "timeout_ms", "aim_area", "volume_keys"))
            o["mode"]?.let { enum("scan.trigger.mode", it, setOf("button", "continuous")) }
            o["timeout_ms"]?.let { int("scan.trigger.timeout_ms", it, 1000, 30000) }
            o["aim_area"]?.let { number("scan.trigger.aim_area", it, 0.2, 1.0) }
            o["volume_keys"]?.let { bool("scan.trigger.volume_keys", it) }
        }

        private fun endpoints(el: JsonElement, scanFields: Set<String>) {
            val o = obj("endpoints", el) ?: return
            keys("endpoints", o, setOf("get_item", "send_op", "send_batch", "get_config"))
            for ((name, value) in o) {
                val path = "endpoints.$name"
                val e = obj(path, value) ?: continue
                keys(path, e, setOf("method", "path"))
                required(path, e, "method", "path")
                e["method"]?.let { enum("$path.method", it, setOf("GET", "POST", "PUT", "PATCH")) }
                val p = e["path"]?.let { string("$path.path", it) }
                if (p != null && !p.startsWith("/")) err("$path.path", "doit commencer par « / ».")
                if (p != null) {
                    val known = if (name == "get_item") scanFields else scanFields + OperationVariables.NAMES
                    val unknown = Templates.variablesOf(p).filter { it !in known }
                    if (unknown.isNotEmpty()) {
                        warn("$path.path", "variable(s) inconnue(s) : ${unknown.joinToString(", ") { "{$it}" }}.")
                    }
                }
            }
        }

        private fun requestMapping(el: JsonElement, scanFields: Set<String>) {
            val map = stringMap("request_mapping", el) ?: return
            val known = scanFields + OperationVariables.NAMES
            for ((key, template) in map) {
                val unknown = Templates.variablesOf(template).filter { it !in known }
                if (unknown.isNotEmpty()) {
                    warn("request_mapping.$key", "variable(s) inconnue(s) : ${unknown.joinToString(", ") { "{$it}" }}.")
                }
            }
        }

        private fun display(el: JsonElement) {
            val o = obj("display", el) ?: return
            keys(
                "display", o,
                setOf("show_fields", "field_labels", "show_image", "image_url", "image_cache", "step_buttons", "allow_set"),
            )
            o["show_fields"]?.let { f ->
                array("display.show_fields", f)?.forEachIndexed { i, item -> fieldName("display.show_fields[$i]", item) }
            }
            o["field_labels"]?.let { stringMap("display.field_labels", it) }
            o["show_image"]?.let { bool("display.show_image", it) }
            o["image_url"]?.let { string("display.image_url", it) }
            o["image_cache"]?.let { bool("display.image_cache", it) }
            o["step_buttons"]?.let { s ->
                array("display.step_buttons", s, maxItems = 3, unique = true)?.forEachIndexed { i, item ->
                    int("display.step_buttons[$i]", item, 1, 10000)
                }
            }
            o["allow_set"]?.let { bool("display.allow_set", it) }
        }

        private fun burst(el: JsonElement) {
            val o = obj("burst", el) ?: return
            keys("burst", o, setOf("enabled", "default_direction", "default_step", "same_code_cooldown_ms", "verify_item"))
            o["enabled"]?.let { bool("burst.enabled", it) }
            o["default_direction"]?.let { enum("burst.default_direction", it, setOf("in", "out")) }
            o["default_step"]?.let { int("burst.default_step", it, 1, null) }
            o["same_code_cooldown_ms"]?.let { int("burst.same_code_cooldown_ms", it, 0, 10000) }
            o["verify_item"]?.let { bool("burst.verify_item", it) }
        }

        private fun sync(el: JsonElement) {
            val o = obj("sync", el) ?: return
            keys("sync", o, setOf("mode", "batch", "max_batch_size", "retry_backoff_s"))
            o["mode"]?.let { enum("sync.mode", it, setOf("auto", "manual")) }
            o["batch"]?.let { bool("sync.batch", it) }
            o["max_batch_size"]?.let { int("sync.max_batch_size", it, 1, 500) }
            o["retry_backoff_s"]?.let { r ->
                array("sync.retry_backoff_s", r, minItems = 1)?.forEachIndexed { i, item ->
                    int("sync.retry_backoff_s[$i]", item, 1, null)
                }
            }
        }

        private fun security(el: JsonElement) {
            val o = obj("security", el) ?: return
            keys("security", o, setOf("config_locked", "config_password_hash", "require_user"))
            val locked = o["config_locked"]?.let { bool("security.config_locked", it) }
            o["config_password_hash"]?.let { h ->
                val s = string("security.config_password_hash", h)
                if (s != null && !ARGON2_PHC.containsMatchIn(s)) {
                    err("security.config_password_hash", "doit être un hash argon2id (format PHC « \$argon2id\$… »), jamais un mot de passe en clair.")
                }
            }
            o["require_user"]?.let { bool("security.require_user", it) }
            if (locked == true && o["config_password_hash"] == null) {
                warn("security", "config verrouillée sans hash de mot de passe : le mot de passe sera défini sur l'appareil.")
            }
        }

        private fun remoteConfig(el: JsonElement) {
            val o = obj("remote_config", el) ?: return
            keys("remote_config", o, setOf("enabled", "url", "auto_update", "signing_public_key"))
            val enabled = o["enabled"]?.let { bool("remote_config.enabled", it) }
            o["url"]?.let { string("remote_config.url", it) }
            o["auto_update"]?.let { bool("remote_config.auto_update", it) }
            o["signing_public_key"]?.let { string("remote_config.signing_public_key", it) }
            if (enabled == true && o["signing_public_key"] == null) {
                warn("remote_config", "config distante activée sans « signing_public_key » : toute config distante sera refusée.")
            }
        }

        // --- Primitives -------------------------------------------------------------------------

        private fun keys(path: String, o: JsonObject, allowed: Set<String>) {
            for (k in o.keys) if (k !in allowed) err(if (path.isEmpty()) k else "$path.$k", "propriété inconnue.")
        }

        private fun required(path: String, o: JsonObject, vararg names: String) {
            for (n in names) if (o[n] == null || o[n] is JsonNull) {
                err(if (path.isEmpty()) n else "$path.$n", "obligatoire.")
            }
        }

        private fun obj(path: String, el: JsonElement): JsonObject? =
            el as? JsonObject ?: run { err(path, "doit être un objet."); null }

        private fun array(
            path: String,
            el: JsonElement,
            minItems: Int = 0,
            maxItems: Int = Int.MAX_VALUE,
            unique: Boolean = false,
        ): JsonArray? {
            val a = el as? JsonArray ?: run { err(path, "doit être une liste."); return null }
            if (a.size < minItems) err(path, "doit contenir au moins $minItems élément(s).")
            if (a.size > maxItems) err(path, "ne peut pas contenir plus de $maxItems éléments.")
            if (unique && a.toSet().size != a.size) err(path, "contient des doublons.")
            return a
        }

        private fun string(path: String, el: JsonElement, minLength: Int = 0, maxLength: Int = Int.MAX_VALUE): String? {
            val p = el as? JsonPrimitive
            if (p == null || !p.isString) {
                err(path, "doit être un texte."); return null
            }
            val s = p.content
            if (s.length < minLength) err(path, "doit contenir au moins $minLength caractère(s).")
            if (s.length > maxLength) err(path, "ne peut pas dépasser $maxLength caractères.")
            return s
        }

        private fun bool(path: String, el: JsonElement): Boolean? {
            val p = el as? JsonPrimitive
            val b = if (p != null && !p.isString) p.booleanOrNull else null
            if (b == null) err(path, "doit valoir true ou false.")
            return b
        }

        private fun int(path: String, el: JsonElement, min: Long?, max: Long?): Long? {
            val n = intOf(el) ?: run { err(path, "doit être un nombre entier."); return null }
            if ((min != null && n < min) || (max != null && n > max)) {
                err(path, rangeMessage(n.toString(), min?.toString(), max?.toString()))
            }
            return n
        }

        private fun number(path: String, el: JsonElement, min: Double, max: Double): Double? {
            val p = el as? JsonPrimitive
            val d = if (p != null && !p.isString) p.content.toDoubleOrNull() else null
            if (d == null) {
                err(path, "doit être un nombre."); return null
            }
            if (d < min || d > max) err(path, rangeMessage(p!!.content, min.toString(), max.toString()))
            return d
        }

        private fun rangeMessage(value: String, min: String?, max: String?): String = when {
            min != null && max != null -> "valeur $value hors limites (entre $min et $max)."
            min != null -> "valeur $value trop petite (minimum $min)."
            else -> "valeur $value trop grande (maximum $max)."
        }

        private fun enum(path: String, el: JsonElement, allowed: Set<String>): String? {
            val p = el as? JsonPrimitive
            if (p == null || !p.isString || p.content !in allowed) {
                val shown = (el as? JsonPrimitive)?.content ?: el.toString()
                err(path, "valeur « $shown » non reconnue (attendu : ${allowed.joinToString(", ")}).")
                return null
            }
            return p.content
        }

        private fun fieldName(path: String, el: JsonElement): String? {
            val s = string(path, el) ?: return null
            if (!FIELD_NAME.matches(s)) {
                err(path, "nom de champ « $s » invalide (minuscules, chiffres et _ ; commence par une lettre ; 40 caractères max).")
                return null
            }
            return s
        }

        private fun stringMap(path: String, el: JsonElement): Map<String, String>? {
            val o = obj(path, el) ?: return null
            val out = LinkedHashMap<String, String>()
            for ((k, v) in o) string("$path.$k", v)?.let { out[k] = it }
            return out
        }

        private fun validUrl(s: String): Boolean = try {
            val uri = URI(s)
            !uri.host.isNullOrEmpty() && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
        } catch (e: Exception) {
            false
        }
    }

    private val SYMBOLOGY_NAMES = setOf("qr_code", "data_matrix", "ean_13", "ean_8", "upc_a", "upc_e", "code_128", "code_39", "itf")

    internal fun intOf(el: JsonElement): Long? {
        val p = el as? JsonPrimitive ?: return null
        if (p.isString) return null
        p.content.toLongOrNull()?.let { return it }
        val d = p.content.toDoubleOrNull() ?: return null
        return if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 9.0E15) d.toLong() else null
    }
}
