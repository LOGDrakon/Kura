package dev.kura.scan

import android.content.Context
import dev.kura.core.config.KuraConfig
import dev.kura.core.net.KuraClient
import dev.kura.core.net.KuraRequests
import dev.kura.core.ops.OperationFactory
import dev.kura.core.sync.SyncEngine
import dev.kura.scan.data.ItemCache
import dev.kura.scan.data.RoomOperationStore
import dev.kura.scan.data.SecretStore
import dev.kura.scan.data.SessionLock
import dev.kura.scan.data.SettingsRepository
import dev.kura.scan.data.db.KuraDatabase
import dev.kura.scan.net.OkHttpTransport
import dev.kura.scan.sync.SyncScheduler

/** Injection manuelle, paresseuse (démarrage rapide sur les téléphones modestes). */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val settings by lazy { SettingsRepository(app) }
    val secrets by lazy { SecretStore(app) }
    val lock by lazy { SessionLock(settings) }
    private val db by lazy { KuraDatabase.build(app) }
    val queueDao by lazy { db.queueDao() }
    val operationStore by lazy { RoomOperationStore(db.queueDao()) }
    val itemCache by lazy { ItemCache(db.itemCacheDao()) }
    val transport by lazy { OkHttpTransport(app, settings) }
    val syncScheduler by lazy { SyncScheduler(app) }

    val operationFactory by lazy {
        OperationFactory(deviceId = { settings.deviceId }, user = { settings.user.value })
    }

    fun requests(config: KuraConfig): KuraRequests =
        KuraRequests(config, { secrets.readOrNull() }, userAgent = "KuraScan/${BuildConfig.VERSION_NAME}")

    fun client(config: KuraConfig): KuraClient = KuraClient(requests(config), transport)

    /** Moteur de synchro unique (mutex partagé entre l'écran et le worker). Nécessite une config chargée. */
    val syncEngine by lazy {
        SyncEngine(
            store = operationStore,
            client = { client(requireConfig()) },
            config = { requireConfig() },
        )
    }

    /** URL du serveur HTTP à faire confirmer avant tout appel réseau, ou `null` si rien à confirmer. */
    fun cleartextToConfirm(): String? {
        val config = settings.config.value ?: return null
        return config.server.baseUrl.takeIf { config.isCleartext && !settings.isCleartextAccepted(it) }
    }

    private fun requireConfig(): KuraConfig = settings.config.value ?: error("aucune configuration chargée")
}
