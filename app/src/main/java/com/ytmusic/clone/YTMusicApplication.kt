package com.ytmusic.clone

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ErrorResult
import com.ytmusic.clone.data.local.DownloadManager
import com.ytmusic.clone.data.local.ExtractorPrefs
import com.ytmusic.clone.data.local.LibraryStore
import com.ytmusic.clone.data.local.PlayerStateStore
import com.ytmusic.clone.data.network.PolicyScope
import com.ytmusic.clone.data.network.SecureHttp
import com.ytmusic.clone.data.network.UrlPolicy
import com.ytmusic.clone.player.MusicPlayerManager
import java.io.IOException

class YTMusicApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ExtractorPrefs.init(this)
        DownloadManager.init(this)
        LibraryStore.init(this)
        PlayerStateStore.init(this)
        MusicPlayerManager.getOrCreatePlayer(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .allowRgb565(false)
            .okHttpClient {
                SecureHttp.newBuilder(PolicyScope.IMAGE).build()
            }
            .components {
                add(Interceptor { chain ->
                    val data = chain.request.data
                    val urlStr = when (data) {
                        is String -> data
                        is android.net.Uri -> data.toString()
                        is okhttp3.HttpUrl -> data.toString()
                        else -> null
                    }
                    if (urlStr != null && (urlStr.startsWith("http://") || urlStr.startsWith("https://"))) {
                        if (!UrlPolicy.isAllowed(urlStr, PolicyScope.IMAGE)) {
                            throw IOException("Blocked image URL violating IMAGE policy: $urlStr")
                        }
                    }
                    chain.proceed(chain.request)
                })
                // maxresdefault.jpg does not exist for many videos (HTTP 404): fall back to smaller sizes
                add(Interceptor { chain ->
                    val first = chain.proceed(chain.request)
                    val data = chain.request.data
                    val urlStr = when (data) {
                        is String -> data
                        is android.net.Uri -> data.toString()
                        is okhttp3.HttpUrl -> data.toString()
                        else -> null
                    }
                    if (first is ErrorResult && urlStr != null &&
                        urlStr.contains("i.ytimg.com/vi/") && urlStr.contains("maxresdefault")
                    ) {
                        var result: coil.request.ImageResult = first
                        for (name in listOf("sddefault", "hqdefault")) {
                            val retry = chain.request.newBuilder()
                                .data(urlStr.replace("maxresdefault", name))
                                .build()
                            result = chain.proceed(retry)
                            if (result !is ErrorResult) break
                        }
                        result
                    } else {
                        first
                    }
                })
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.08)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100MB disk cache for large playlists
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }
}
