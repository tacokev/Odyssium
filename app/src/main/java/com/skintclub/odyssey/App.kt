package com.skintclub.odyssey

import android.app.Application
import android.os.Build
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.datastore.preferences.core.edit
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.skintclub.innertube.YouTube
import com.skintclub.innertube.models.YouTubeLocale
import com.skintclub.odyssey.constants.ContentCountryKey
import com.skintclub.odyssey.constants.ContentLanguageKey
import com.skintclub.odyssey.constants.CountryCodeToName
import com.skintclub.odyssey.constants.InnerTubeCookieKey
import com.skintclub.odyssey.constants.LanguageCodeToName
import com.skintclub.odyssey.constants.MaxImageCacheSizeKey
import com.skintclub.odyssey.constants.ProxyEnabledKey
import com.skintclub.odyssey.constants.ProxyTypeKey
import com.skintclub.odyssey.constants.ProxyUrlKey
import com.skintclub.odyssey.constants.SYSTEM_DEFAULT
import com.skintclub.odyssey.constants.VisitorDataKey
import com.skintclub.odyssey.extensions.toEnum
import com.skintclub.odyssey.extensions.toInetSocketAddress
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.get
import com.skintclub.odyssey.utils.reportException
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.Proxy
import java.util.Locale

@HiltAndroidApp
class App :
    Application(),
    ImageLoaderFactory {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().replace("-Hant", "") // replace zh-Hant-* to zh-*
        YouTube.locale =
            YouTubeLocale(
                gl =
                    dataStore[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }
                        ?: locale.country.takeIf { it in CountryCodeToName }
                        ?: "US",
                hl =
                    dataStore[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }
                        ?: locale.language.takeIf { it in LanguageCodeToName }
                        ?: languageTag.takeIf { it in LanguageCodeToName }
                        ?: "en",
            )

        if (dataStore[ProxyEnabledKey] == true) {
            try {
                YouTube.proxy =
                    Proxy(
                        dataStore[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP),
                        dataStore[ProxyUrlKey]!!.toInetSocketAddress(),
                    )
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to parse proxy url.", LENGTH_SHORT).show()
                reportException(e)
            }
        }

        GlobalScope.launch {
            dataStore.data
                .map { it[VisitorDataKey] }
                .distinctUntilChanged()
                .collect { visitorData ->
                    YouTube.visitorData = visitorData
                        ?.takeIf { it != "null" } // Previously visitorData was sometimes saved as "null" due to a bug
                        ?: YouTube.visitorData().getOrNull()?.also { newVisitorData ->
                            dataStore.edit { settings ->
                                settings[VisitorDataKey] = newVisitorData
                            }
                        } ?: YouTube.DEFAULT_VISITOR_DATA
                }
        }
        GlobalScope.launch {
            dataStore.data
                .map { it[InnerTubeCookieKey] }
                .distinctUntilChanged()
                .collect { cookie ->
                    YouTube.cookie = cookie
                }
        }
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
