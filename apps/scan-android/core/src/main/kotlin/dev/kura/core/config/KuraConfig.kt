package dev.kura.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modèle de `kura-config.json` (miroir de `spec/config.schema.json`).
 * Les valeurs par défaut sont celles du schéma. Utiliser [KuraConfig.parse] pour lire et valider un fichier.
 */
@Serializable
data class KuraConfig(
    @SerialName("kura_config_version") val version: Int = 1,
    val company: String? = null,
    val server: ServerConfig,
    val scan: ScanConfig = ScanConfig(),
    val endpoints: EndpointsConfig = EndpointsConfig(),
    @SerialName("request_mapping") val requestMapping: Map<String, String>? = null,
    @SerialName("response_mapping") val responseMapping: Map<String, String>? = null,
    val display: DisplayConfig = DisplayConfig(),
    val burst: BurstConfig = BurstConfig(),
    val sync: SyncConfig = SyncConfig(),
    val security: SecurityConfig = SecurityConfig(),
    @SerialName("remote_config") val remoteConfig: RemoteConfig = RemoteConfig(),
) {
    /** Points d'accès effectifs (surcharges de la config, sinon contrat Kura standard). */
    val effectiveEndpoints: ResolvedEndpoints
        get() = ResolvedEndpoints(
            getItem = endpoints.getItem ?: Endpoint(HttpMethod.GET, "/items/{ref}"),
            sendOp = endpoints.sendOp ?: Endpoint(HttpMethod.POST, "/operations"),
            sendBatch = endpoints.sendBatch ?: Endpoint(HttpMethod.POST, "/operations/batch"),
            getConfig = endpoints.getConfig ?: Endpoint(HttpMethod.GET, "/config"),
        )

    /** `true` si le serveur parle le contrat Kura (pas de `request_mapping`). */
    val usesKuraContract: Boolean get() = requestMapping.isNullOrEmpty()

    /**
     * Envoi par lot : seulement si `sync.batch` et si le serveur parle le contrat Kura,
     * ou si un `send_batch` explicite est configuré avec un `request_mapping`.
     */
    val usesBatch: Boolean get() = sync.batch && (usesKuraContract || endpoints.sendBatch != null)

    val isCleartext: Boolean get() = server.baseUrl.startsWith("http://", ignoreCase = true)

    companion object {
        /** Lit et valide une config (messages d'erreur en français). */
        fun parse(json: String): ConfigParseResult = ConfigParser.parse(json)
    }
}

@Serializable
data class ServerConfig(
    @SerialName("base_url") val baseUrl: String,
    val auth: AuthConfig = AuthConfig(),
    @SerialName("timeout_ms") val timeoutMs: Int = 8000,
)

@Serializable
enum class AuthType {
    @SerialName("none") NONE,
    @SerialName("bearer") BEARER,
    @SerialName("basic") BASIC,
    @SerialName("api_key") API_KEY,
}

@Serializable
enum class TokenRef {
    @SerialName("secure_store") SECURE_STORE,
    @SerialName("mdm") MDM,
}

@Serializable
data class AuthConfig(
    val type: AuthType = AuthType.NONE,
    @SerialName("token_ref") val tokenRef: TokenRef = TokenRef.SECURE_STORE,
    val header: String = "X-Api-Key",
    val username: String? = null,
)

@Serializable
enum class CodeFormat {
    @SerialName("id") ID,
    @SerialName("delimited") DELIMITED,
    @SerialName("json") JSON,
}

@Serializable
data class ScanConfig(
    val symbologies: List<String> = DEFAULT_SYMBOLOGIES,
    val format: CodeFormat = CodeFormat.ID,
    val delimiter: String = ";",
    val fields: List<String> = emptyList(),
    @SerialName("id_field") val idField: String = "ref",
    val prefix: String? = null,
    val trigger: TriggerConfig = TriggerConfig(),
) {
    companion object {
        val DEFAULT_SYMBOLOGIES = listOf("qr_code", "ean_13", "ean_8", "code_128")
    }
}

@Serializable
enum class TriggerMode {
    @SerialName("button") BUTTON,
    @SerialName("continuous") CONTINUOUS,
}

@Serializable
data class TriggerConfig(
    val mode: TriggerMode = TriggerMode.BUTTON,
    @SerialName("timeout_ms") val timeoutMs: Int = 6000,
    @SerialName("aim_area") val aimArea: Double = 0.6,
    @SerialName("volume_keys") val volumeKeys: Boolean = true,
)

@Serializable
enum class HttpMethod { GET, POST, PUT, PATCH }

@Serializable
data class Endpoint(val method: HttpMethod, val path: String)

@Serializable
data class EndpointsConfig(
    @SerialName("get_item") val getItem: Endpoint? = null,
    @SerialName("send_op") val sendOp: Endpoint? = null,
    @SerialName("send_batch") val sendBatch: Endpoint? = null,
    @SerialName("get_config") val getConfig: Endpoint? = null,
)

data class ResolvedEndpoints(
    val getItem: Endpoint,
    val sendOp: Endpoint,
    val sendBatch: Endpoint,
    val getConfig: Endpoint,
)

@Serializable
data class DisplayConfig(
    @SerialName("show_fields") val showFields: List<String>? = null,
    @SerialName("field_labels") val fieldLabels: Map<String, String> = emptyMap(),
    @SerialName("show_image") val showImage: Boolean = false,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("image_cache") val imageCache: Boolean = true,
    @SerialName("step_buttons") val stepButtons: List<Int> = listOf(1),
    @SerialName("allow_set") val allowSet: Boolean = true,
)

@Serializable
enum class Direction {
    @SerialName("in") IN,
    @SerialName("out") OUT;

    /** Signe appliqué au pas : entrée = +, sortie = −. */
    val sign: Int get() = if (this == IN) 1 else -1
}

@Serializable
data class BurstConfig(
    val enabled: Boolean = true,
    @SerialName("default_direction") val defaultDirection: Direction = Direction.OUT,
    @SerialName("default_step") val defaultStep: Int = 1,
    @SerialName("same_code_cooldown_ms") val sameCodeCooldownMs: Int = 1500,
    @SerialName("verify_item") val verifyItem: Boolean = false,
)

@Serializable
enum class SyncMode {
    @SerialName("auto") AUTO,
    @SerialName("manual") MANUAL,
}

@Serializable
data class SyncConfig(
    val mode: SyncMode = SyncMode.AUTO,
    val batch: Boolean = true,
    @SerialName("max_batch_size") val maxBatchSize: Int = 50,
    @SerialName("retry_backoff_s") val retryBackoffS: List<Int> = listOf(5, 30, 120),
)

@Serializable
data class SecurityConfig(
    @SerialName("config_locked") val configLocked: Boolean = false,
    @SerialName("config_password_hash") val configPasswordHash: String? = null,
    @SerialName("require_user") val requireUser: Boolean = false,
)

@Serializable
data class RemoteConfig(
    val enabled: Boolean = false,
    val url: String? = null,
    @SerialName("auto_update") val autoUpdate: Boolean = false,
    @SerialName("signing_public_key") val signingPublicKey: String? = null,
)
