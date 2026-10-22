package dev.kura.scan

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Aucun travail lourd au démarrage : tout est créé à la demande par [AppContainer]
 * (WorkManager et Coil compris, initialisés au premier usage).
 */
class KuraApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    /** Images : uniquement si `display.show_image` ; petits caches pour les téléphones à 1–2 Go de RAM. */
    override fun newImageLoader(): ImageLoader {
        val cacheOnDisk = container.settings.config.value?.display?.imageCache != false
        return ImageLoader.Builder(this)
            .okHttpClient { container.transport.imageClient }
            .components { add(SvgDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
            .apply {
                if (cacheOnDisk) {
                    diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(32L * 1024 * 1024).build() }
                } else {
                    diskCache(null)
                }
            }
            .crossfade(false)
            .respectCacheHeaders(true)
            .build()
    }
}

val android.content.Context.kura: AppContainer
    get() = (applicationContext as KuraApplication).container
