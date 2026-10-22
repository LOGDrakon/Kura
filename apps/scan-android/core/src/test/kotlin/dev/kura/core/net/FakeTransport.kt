package dev.kura.core.net

import java.io.IOException

/** Transport scripté : chaque appel consomme la réponse suivante (ou lève l'exception prévue). */
class FakeTransport(vararg responses: Any) : HttpTransport {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequestSpec>()

    override suspend fun execute(request: HttpRequestSpec): HttpResponseData {
        requests += request
        return when (val next = queue.removeFirstOrNull() ?: error("pas de réponse prévue pour $request")) {
            is HttpResponseData -> next
            is IOException -> throw next
            is Function1<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (next as (HttpRequestSpec) -> HttpResponseData)(request)
            }
            else -> error("réponse inattendue $next")
        }
    }
}

fun ok(body: String) = HttpResponseData(200, body, "application/json")
