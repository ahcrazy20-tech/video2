package com.ahcrazy.video2

import android.app.Application
import com.ahcrazy.video2.data.AppSettings
import com.ahcrazy.video2.data.DownloadManager
import com.ahcrazy.video2.data.ExternalResolver
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.data.MagicSearchRepository
import com.ahcrazy.video2.data.ResolverType
import com.ahcrazy.video2.data.SmartDownloads
import com.ahcrazy.video2.data.TranslationManager

/** Manual DI container (the iOS app injects the same stores as EnvironmentObjects). */
class Video2Application : Application() {
    internal val externalResolver by lazy { ExternalResolver() }
    internal val smartDownloads by lazy {
        SmartDownloads(externalResolver::pingInvidious,
            resolve = { endpoint, video, height, title ->
                externalResolver.resolve(ResolverType.INVIDIOUS, endpoint, video, height, title, null)
            },
            check = { candidate, height -> downloads.probe(candidate.media, candidate.auth, height, automatic = true) })
    }
    lateinit var settings: AppSettings
    lateinit var library: LibraryStore
    lateinit var downloads: DownloadManager
    lateinit var translations: TranslationManager
    lateinit var magic: MagicSearchRepository

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        library = LibraryStore(this)
        downloads = DownloadManager(this, library)
        translations = TranslationManager(this, library, settings)
        magic = MagicSearchRepository()
    }
}
fun android.app.Activity.app(): Video2Application = application as Video2Application
