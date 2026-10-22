package dev.kura.core.sync

import dev.kura.core.TestSupport
import dev.kura.core.config.AuthConfig
import dev.kura.core.config.KuraConfig
import dev.kura.core.config.ServerConfig
import dev.kura.core.config.SyncConfig
import dev.kura.core.net.FakeTransport
import dev.kura.core.net.HttpResponseData
import dev.kura.core.net.ItemLookup
import dev.kura.core.net.KuraClient
import dev.kura.core.net.KuraRequests
import dev.kura.core.net.SendOutcome
import dev.kura.core.net.OutgoingOperation
import dev.kura.core.net.ok
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private val factory = TestSupport.factory()

    @Test
    fun `statuts serveur vers actions de file`() {
        assertEquals(QueueAction.Remove(), SyncPolicy.actionFor(OperationResult("a", ResultStatus.APPLIED)))
        assertEquals(QueueAction.Remove(), SyncPolicy.actionFor(OperationResult("a", ResultStatus.DUPLICATE)))
        assertEquals(QueueAction.Remove(Notice.SUPERSEDED), SyncPolicy.actionFor(OperationResult("a", ResultStatus.SUPERSEDED)))
        assertEquals(
            QueueAction.KeepInError("unknown_item", "article inconnu : X"),
            SyncPolicy.actionFor(OperationResult("a", ResultStatus.REJECTED, "unknown_item", "article inconnu : X")),
        )
        assertEquals(QueueAction.KeepInError("invalid", null), SyncPolicy.actionFor(OperationResult("a", ResultStatus.REJECTED)))
    }

    @Test
    fun `echecs de transport vers actions`() {
        assertIs<QueueAction.Retry>(SyncPolicy.actionFor(Failure(FailureKind.NETWORK, null, "x")))
        assertIs<QueueAction.Retry>(SyncPolicy.actionFor(Failure(FailureKind.SERVER, 503, "x")))
        assertIs<QueueAction.Retry>(SyncPolicy.actionFor(Failure(FailureKind.AUTH, 401, "x")))
        assertEquals(QueueAction.KeepInError("invalid", "x"), SyncPolicy.actionFor(Failure(FailureKind.CLIENT, 400, "x")))
        assertEquals(QueueAction.KeepInError("conflict", "x"), SyncPolicy.actionFor(Failure(FailureKind.CLIENT, 409, "x")))
        assertEquals(QueueAction.KeepInError("server_error", "x"), SyncPolicy.actionFor(Failure(FailureKind.PROTOCOL, 200, "x")))
        assertNull(SyncPolicy.classifyHttpStatus(204))
        assertEquals(FailureKind.SERVER, SyncPolicy.classifyHttpStatus(429))
        assertEquals(FailureKind.SERVER, SyncPolicy.classifyHttpStatus(408))
        assertEquals(FailureKind.AUTH, SyncPolicy.classifyHttpStatus(403))
        assertEquals(FailureKind.CLIENT, SyncPolicy.classifyHttpStatus(422))
    }

    @Test
    fun `backoff et lots`() {
        val b = Backoff(listOf(5, 30, 120))
        assertEquals(listOf(5000L, 30000L, 120000L, 120000L, 120000L), (1..5).map { b.delayMs(it) })
        assertEquals(5000L, b.delayMs(0))
        assertEquals(listOf(5000L), (1..1).map { Backoff(emptyList()).delayMs(it) })
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), SyncPolicy.batches(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(1, SyncPolicy.batches((1..500).toList(), 1000).size)
    }

    private fun config(batch: Boolean, max: Int = 50) =
        KuraConfig(server = ServerConfig("https://s.fr", AuthConfig()), sync = SyncConfig(batch = batch, maxBatchSize = max, retryBackoffS = listOf(5, 30)))

    private fun engine(store: OperationStore, transport: FakeTransport, cfg: KuraConfig, now: () -> Long = { 1000 }) =
        SyncEngine(store, { KuraClient(KuraRequests(cfg, { null }), transport) }, { cfg }, now)

    @Test
    fun `vidage unitaire - applied, superseded, rejected`() = runTest {
        val store = InMemoryOperationStore()
        val a = factory.delta("A", -1); val b = factory.set("B", 3); val c = factory.delta("X", 1)
        listOf(a, b, c).forEach { store.enqueue(it) }
        val t = FakeTransport(
            ok("""{"op_id":"${a.opId}","status":"applied","quantity":249}"""),
            ok("""{"op_id":"${b.opId}","status":"superseded","quantity":7}"""),
            ok("""{"op_id":"${c.opId}","status":"rejected","reason":"unknown_item","message":"article inconnu : X"}"""),
        )
        val report = engine(store, t, config(batch = false)).drain()
        assertEquals(3, t.requests.size)
        assertTrue(t.requests.all { it.url == "https://s.fr/operations" })
        assertEquals(2, report.sentCount)
        assertEquals(mapOf("A" to 249L, "B" to 7L), report.quantities)
        assertEquals(Notice.SUPERSEDED, (report.outcomes[b.opId] as OpOutcome.Sent).notice)
        assertEquals(OpOutcome.InError("unknown_item", "article inconnu : X"), report.outcomes[c.opId])
        assertEquals(listOf(c.opId), store.all.map { it.op.opId })
        assertEquals(QueueState.ERROR, store.all.single().state)
        assertNull(report.retryInMs)
    }

    @Test
    fun `vidage par lots de max_batch_size`() = runTest {
        val store = InMemoryOperationStore()
        val ops = (1..5).map { factory.delta("A", -1) }
        ops.forEach { store.enqueue(it) }
        val t = FakeTransport(
            { req: dev.kura.core.net.HttpRequestSpec -> batchReply(req) },
            { req: dev.kura.core.net.HttpRequestSpec -> batchReply(req) },
            { req: dev.kura.core.net.HttpRequestSpec -> batchReply(req) },
        )
        val report = engine(store, t, config(batch = true, max = 2)).drain()
        assertEquals(3, t.requests.size)
        assertTrue(t.requests.all { it.url == "https://s.fr/operations/batch" })
        assertEquals(5, report.sentCount)
        assertTrue(store.all.isEmpty())
    }

    private fun batchReply(req: dev.kura.core.net.HttpRequestSpec): HttpResponseData {
        val ops = Json.parseToJsonElement(req.body!!).jsonObject["operations"]!!.jsonArray
        val results = ops.joinToString(",") { """{"op_id":${it.jsonObject["op_id"]},"status":"applied"}""" }
        return ok("""{"results":[$results]}""")
    }

    @Test
    fun `lot - resultat manquant reessaye, resultats par position`() = runTest {
        val store = InMemoryOperationStore()
        val a = factory.delta("A", 1); val b = factory.delta("B", 1)
        store.enqueue(a); store.enqueue(b)
        val t = FakeTransport(ok("""{"results":[{"op_id":"${a.opId}","status":"duplicate"}]}"""))
        val report = engine(store, t, config(batch = true)).drain()
        assertIs<OpOutcome.Sent>(report.outcomes[a.opId])
        assertIs<OpOutcome.WillRetry>(report.outcomes[b.opId])
        assertEquals(5000L, report.retryInMs)

        val client = KuraClient(KuraRequests(config(true), { null }), FakeTransport(ok("""{"results":[{"status":"applied"},{"status":"rejected"}]}""")))
        val out = client.sendBatch(listOf(OutgoingOperation(a), OutgoingOperation(b))).outcomes
        assertEquals(ResultStatus.APPLIED, (out[a.opId] as SendOutcome.Result).result.status)
        assertEquals(b.opId, (out[b.opId] as SendOutcome.Result).result.opId)
    }

    @Test
    fun `reseau absent - arret, backoff croissant, puis succes`() = runTest {
        val store = InMemoryOperationStore()
        val a = factory.delta("A", 1); val b = factory.delta("B", 1)
        store.enqueue(a); store.enqueue(b)
        var now = 0L
        val t = FakeTransport(
            IOException("timeout"),
            IOException("timeout"),
            ok("""{"op_id":"${a.opId}","status":"applied"}"""),
            ok("""{"op_id":"${b.opId}","status":"applied"}"""),
        )
        val e = engine(store, t, config(batch = false)) { now }
        val r1 = e.drain()
        assertEquals(1, t.requests.size) // arrêt au premier échec réseau
        assertEquals(5000L, r1.retryInMs)
        assertEquals(1, store.get(a.opId)!!.attempts)
        assertTrue(store.get(a.opId)!!.lastMessage!!.startsWith("Serveur injoignable"))

        now = 1000
        val r2 = e.drain() // b est dû, a pas encore
        assertEquals(b.opId, Json.parseToJsonElement(t.requests[1].body!!).jsonObject["op_id"]!!.toString().trim('"'))
        assertEquals(5000L, r2.retryInMs)

        now = 60_000
        val r3 = e.drain()
        assertEquals(2, r3.sentCount)
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `401 - authentification signalee, file conservee`() = runTest {
        val store = InMemoryOperationStore()
        val a = factory.delta("A", 1)
        store.enqueue(a)
        val t = FakeTransport(HttpResponseData(401, """{"error":"unauthorized","message":"Bearer token absent ou invalide"}"""))
        val r = engine(store, t, config(batch = false)).drain()
        assertTrue(r.authFailed)
        assertEquals("Authentification refusée (401) : Bearer token absent ou invalide", (r.outcomes[a.opId] as OpOutcome.WillRetry).message)
        assertEquals(QueueState.PENDING, store.get(a.opId)!!.state)
    }

    @Test
    fun `serveur hors contrat - 2xx vaut applied, 400 en erreur`() = runTest {
        val cfg = config(batch = true).copy(requestMapping = mapOf("article" to "{item_id}"))
        val store = InMemoryOperationStore()
        val a = factory.delta("A", 1); val b = factory.delta("B", 1)
        store.enqueue(a); store.enqueue(b)
        val t = FakeTransport(HttpResponseData(201, ""), HttpResponseData(400, """{"message":"article manquant"}"""))
        val r = engine(store, t, cfg).drain()
        assertEquals(Json.parseToJsonElement("""{"article":"A"}"""), Json.parseToJsonElement(t.requests[0].body!!))
        assertEquals("https://s.fr/operations", t.requests[0].url) // pas de lot sans send_batch explicite
        assertIs<OpOutcome.Sent>(r.outcomes[a.opId])
        assertEquals(OpOutcome.InError("invalid", "Erreur HTTP 400 : article manquant"), r.outcomes[b.opId])
    }

    @Test
    fun `contrat Kura - reponse illisible gardee en erreur`() = runTest {
        val store = InMemoryOperationStore()
        val a = factory.delta("A", 1)
        store.enqueue(a)
        val r = engine(store, FakeTransport(ok("<html>")), config(batch = false)).drain()
        assertEquals("server_error", (r.outcomes[a.opId] as OpOutcome.InError).reason)
        store.requeue(a.opId, 0)
        assertEquals(QueueState.PENDING, store.get(a.opId)!!.state)
    }

    @Test
    fun `lecture d'une fiche`() = runTest {
        val cfg = config(false).copy(responseMapping = mapOf("quantity" to "stock.dispo"))
        val t = FakeTransport(
            ok("""{"ref":"A","name":"Vis","stock":{"dispo":4}}"""),
            HttpResponseData(404, """{"error":"not_found"}"""),
            HttpResponseData(503, ""),
            ok("pas du json"),
        )
        val c = KuraClient(KuraRequests(cfg, { null }), t)
        val found = assertIs<ItemLookup.Found>(c.getItem(mapOf("ref" to "A"), "A"))
        assertEquals(4, found.item.quantity)
        assertEquals(ItemLookup.NotFound, c.getItem(mapOf("ref" to "B"), "B"))
        assertEquals(FailureKind.SERVER, (c.getItem(mapOf("ref" to "C"), "C") as ItemLookup.Failed).failure.kind)
        assertEquals(FailureKind.PROTOCOL, (c.getItem(mapOf("ref" to "D"), "D") as ItemLookup.Failed).failure.kind)
    }

    @Test
    fun `config distante`() = runTest {
        val c = KuraClient(
            KuraRequests(config(false), { null }),
            FakeTransport(ok("""{"config":"{\"a\":1}","signature":"c2ln"}"""), HttpResponseData(404, ""), IOException("x")),
        )
        assertEquals("{\"a\":1}" to "c2ln", c.getRemoteConfig())
        assertNull(c.getRemoteConfig())
        assertNull(c.getRemoteConfig())
        assertEquals(JsonObject(emptyMap()), JsonObject(emptyMap()))
    }
}
