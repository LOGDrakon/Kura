package dev.kura.scan.net

import android.content.Context
import dev.kura.core.net.HttpRequestSpec
import dev.kura.core.net.HttpResponseData
import dev.kura.core.net.HttpTransport
import dev.kura.scan.data.SettingsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Erreur levée quand le serveur est en HTTP et que l'avertissement n'a pas été accepté. */
class CleartextNotAcceptedException(val baseUrl: String) : IOException("Connexion HTTP non confirmée : $baseUrl")

/** Implémentation OkHttp du transport de `:core`. Un seul client partagé (pool de connexions, peu de mémoire). */
class OkHttpTransport(context: Context, private val settings: SettingsRepository) : HttpTransport {
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(false) // ne jamais renvoyer le jeton vers une autre adresse
            .build()
    }
    private val clients = HashMap<Int, OkHttpClient>()

    private fun clientFor(timeoutMs: Int): OkHttpClient = synchronized(clients) {
        clients.getOrPut(timeoutMs) {
            base.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs * 2L, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    /** Client pour Coil (images) : mêmes règles (pas de redirection, HTTP seulement après confirmation). */
    val imageClient: OkHttpClient by lazy {
        base.newBuilder()
            .addInterceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps) {
                    val baseUrl = settings.config.value?.server?.baseUrl
                    if (baseUrl == null || !settings.isCleartextAccepted(baseUrl)) throw CleartextNotAcceptedException(url.toString())
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    override suspend fun execute(request: HttpRequestSpec): HttpResponseData {
        if (request.url.startsWith("http://", ignoreCase = true)) {
            val config = settings.config.value
            val baseUrl = config?.server?.baseUrl
            if (baseUrl == null || !settings.isCleartextAccepted(baseUrl)) throw CleartextNotAcceptedException(baseUrl ?: request.url)
        }
        val body = request.body?.toRequestBody(JSON)
        val okRequest = Request.Builder()
            .url(request.url)
            .apply { request.headers.forEach { (k, v) -> header(k, v) } }
            .method(request.method, body ?: if (request.method == "GET") null else ByteArray(0).toRequestBody(null))
            .build()
        val call = clientFor(request.timeoutMs).newCall(okRequest)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            HttpResponseData(it.code, it.body?.string().orEmpty().take(MAX_BODY), it.header("Content-Type"))
                        }
                    } catch (e: IOException) {
                        cont.resumeWithException(e)
                        return
                    }
                    cont.resume(result)
                }
            })
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_BODY = 1_000_000
    }
}
