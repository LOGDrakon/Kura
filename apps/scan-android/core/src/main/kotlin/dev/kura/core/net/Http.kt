package dev.kura.core.net

import java.io.IOException

/** Requête HTTP décrite indépendamment de la bibliothèque (OkHttp sur Android, HttpClient JDK en test). */
data class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
    val timeoutMs: Int = 8000,
) {
    override fun toString(): String =
        "HttpRequestSpec($method $url, headers=${headers.keys}, body=${body?.length ?: 0} car.)" // jamais les secrets
}

data class HttpResponseData(val status: Int, val body: String, val contentType: String? = null) {
    val isSuccessful: Boolean get() = status in 200..299
}

/**
 * Transport HTTP. Implémenté par l'appli (OkHttp). Doit lever [IOException] en cas d'erreur réseau
 * (hôte injoignable, délai dépassé…) et renvoyer toute réponse HTTP, même 4xx/5xx.
 */
fun interface HttpTransport {
    @Throws(IOException::class)
    suspend fun execute(request: HttpRequestSpec): HttpResponseData
}
