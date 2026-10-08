package com.skintclub.odyssey

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.skintclub.odyssey.constants.MaxImageCacheSizeKey
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.get
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class App :
    Application(),
    ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }

    override fun newImageLoader() =
        ImageLoader
            .Builder(this)
            .crossfade(true)
            .respectCacheHeaders(false)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            // Odyssey's album grid can hold 100+ covers; the default 25%-of-heap memory
            // cache gets thrashed by that many thumbnails, forcing repeat decodes (and
            // the frame drops that come with it) every time you scroll back through them.
            .memoryCache(
                MemoryCache
                    .Builder(this)
                    .maxSizePercent(0.35)
                    .build(),
            ).diskCache(
                DiskCache
                    .Builder()
                    .directory(cacheDir.resolve("coil"))
                    .maxSizeBytes((dataStore[MaxImageCacheSizeKey] ?: 512) * 1024 * 1024L)
                    .build(),
            ).build()
}
