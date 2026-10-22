package dev.kura.core.net

import dev.kura.core.TestSupport
import dev.kura.core.config.AuthConfig
import dev.kura.core.config.AuthType
import dev.kura.core.config.KuraConfig
import dev.kura.core.config.ServerConfig
import dev.kura.core.config.SyncConfig
import dev.kura.core.ops.OperationFactory
import dev.kura.core.ops.PendingStepper
import dev.kura.core.sync.InMemoryOperationStore
import dev.kura.core.sync.Notice
import dev.kura.core.sync.OpOutcome
import dev.kura.core.sync.ResultStatus
import dev.kura.core.sync.SyncEngine
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Transport de test sur `java.net.http` (absent d'Android : l'appli utilise OkHttp). */
class JdkTransport : HttpTransport {
    private val client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()

    override suspend fun execute(request: HttpRequestSpec): HttpResponseData {
        val builder = HttpRequest.newBuilder(URI(request.url)).timeout(Duration.ofMillis(request.timeoutMs.toLong()))
        request.headers.filterKeys { !it.equals("User-Agent", true) }.forEach { (k, v) -> builder.header(k, v) }
        builder.method(
            request.method,
            request.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody(),
        )
        return try {
            val r = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
            HttpResponseData(r.statusCode(), r.body(), r.headers().firstValue("content-type").orElse(null))
        } catch (e: java.net.http.HttpTimeoutException) {
            throw IOException(e)
        } catch (e: java.net.ConnectException) {
            throw IOException(e)
        }
    }
}

/**
 * Bout en bout contre `tools/mock-server` (Node). Ignoré si `node` est absent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockServerIntegrationTest {
    private var process: Process? = null
    private var port = 0
    private val token = "jeton-test"

    @BeforeAll
    fun start() {
        val server = File(TestSupport.repoRoot, "tools/mock-server/server.js")
        val nodeOk = server.isFile && try {
            ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS)
        } catch (e: IOException) {
            false
        }
        assumeTrue(nodeOk, "node ou tools/mock-server absent : test d'intégration ignoré")
        port = ServerSocket(0).use { it.localPort }
        val pb = ProcessBuilder("node", server.absolutePath).redirectErrorStream(true)
        pb.environment().putAll(mapOf("PORT" to "$port", "HOST" to "127.0.0.1", "KURA_MOCK_TOKEN" to token))
        process = pb.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket("127.0.0.1", port).close()
                return
            } catch (e: IOException) {
                Thread.sleep(50)
            }
        }
        error("le serveur de démo ne démarre pas")
    }

    @AfterAll
    fun stop() {
        process?.destroy()
        process?.waitFor(5, TimeUnit.SECONDS)
    }

    private fun config(batch: Boolean) = KuraConfig(
        server = ServerConfig("http://127.0.0.1:$port", AuthConfig(AuthType.BEARER)),
        sync = SyncConfig(batch = batch, maxBatchSize = 2),
    )

    private fun client(cfg: KuraConfig, secret: String? = token) = KuraClient(KuraRequests(cfg, { secret }), JdkTransport())

    @Test
    fun `fiche, validation et envoi unitaire`() = runBlocking {
        val cfg = config(batch = false)
        val api = client(cfg)
        val found = assertIs<ItemLookup.Found>(api.getItem(mapOf("ref" to "REF-0042"), "REF-0042"))
        assertEquals("Vis M6 x 20 inox", found.item.name)
        val q0 = found.item.quantity!!
        assertEquals("/items/REF-0042/image", found.item.imageUrl)
        assertEquals(ItemLookup.NotFound, api.getItem(mapOf("ref" to "NOPE"), "NOPE"))

        val factory = OperationFactory({ "test-device" }, { "jdupont" })
        val op = PendingStepper(q0).step(1).step(1).step(-5).toOperation("REF-0042", factory)!!
        val store = InMemoryOperationStore().apply { enqueue(op) }
        val report = SyncEngine(store, { api }, { cfg }).drain()
        val sent = assertIs<OpOutcome.Sent>(report.outcomes[op.opId])
        assertEquals(ResultStatus.APPLIED, sent.result.status)
        assertEquals(q0 - 3, sent.result.quantity)

        // Renvoi du même op_id : duplicate, quantité inchangée.
        val again = assertIs<SendOutcome.Result>(api.send(OutgoingOperation(op)))
        assertEquals(ResultStatus.DUPLICATE, again.result.status)
        assertEquals(q0 - 3, again.result.quantity)
    }

    @Test
    fun `lot, set perime et article inconnu`() = runBlocking {
        val cfg = config(batch = true)
        val api = client(cfg)
        val late = OperationFactory({ "d" }, { null }, Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC))
        val early = OperationFactory({ "d" }, { null }, Clock.fixed(Instant.parse("2026-10-22T11:00:00Z"), ZoneOffset.UTC))
        val store = InMemoryOperationStore()
        val newer = late.set("REF-0100", 10)
        val older = early.set("REF-0100", 99)
        val unknown = late.delta("INCONNU", 1)
        val delta = late.delta("REF-0043", -2)
        listOf(newer, older, unknown, delta).forEach { store.enqueue(it) }
        val report = SyncEngine(store, { api }, { cfg }).drain()
        assertEquals(Notice.SUPERSEDED, (report.outcomes[older.opId] as OpOutcome.Sent).notice)
        assertEquals(10L, report.quantities["REF-0100"])
        assertEquals("unknown_item", (report.outcomes[unknown.opId] as OpOutcome.InError).reason)
        assertIs<OpOutcome.Sent>(report.outcomes[delta.opId])
        assertEquals(listOf(unknown.opId), store.all.map { it.op.opId })
    }

    @Test
    fun `jeton absent - 401 et file conservee`() = runBlocking {
        val cfg = config(batch = false)
        val store = InMemoryOperationStore().apply { enqueue(OperationFactory({ null }, { null }).delta("REF-0042", 1)) }
        val report = SyncEngine(store, { client(cfg, secret = null) }, { cfg }).drain()
        assertTrue(report.authFailed)
        assertEquals(1, store.all.size)
    }

    @Test
    fun `serveur injoignable - nouvel essai`() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        val cfg = config(false).copy(server = ServerConfig("http://127.0.0.1:$closed", timeoutMs = 2000))
        val store = InMemoryOperationStore().apply { enqueue(OperationFactory({ null }, { null }).delta("REF-0042", 1)) }
        val report = SyncEngine(store, { client(cfg) }, { cfg }).drain()
        assertEquals(5000L, report.retryInMs)
    }
}
