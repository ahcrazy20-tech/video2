package com.ahcrazy.video2

import android.app.Application
import com.ahcrazy.video2.core.media.DownloadCooldowns
import com.ahcrazy.video2.data.AppSettings
import com.ahcrazy.video2.data.DownloadManager
import com.ahcrazy.video2.data.ExternalResolver
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.data.MagicSearchRepository
import com.ahcrazy.video2.data.ResolverType
import com.ahcrazy.video2.data.SmartDownloads
import com.ahcrazy.video2.data.SmartServers
import com.ahcrazy.video2.data.TranslationManager
import com.ahcrazy.video2.data.backup.LibraryBackup
import com.ahcrazy.video2.data.offline.OfflineModels
import com.ahcrazy.video2.data.offline.OfflineWork

/** Manual DI container (the iOS app injects the same stores as EnvironmentObjects). */
class Video2Application : Application() {
    internal val downloadCooldowns = DownloadCooldowns(SmartServers::cooldownGroup)
    internal val externalResolver by lazy { ExternalResolver(cooldowns = downloadCooldowns) }
    internal val smartDownloads by lazy {
        SmartDownloads(resolve = { endpoint, video, height, title ->
                externalResolver.resolve(ResolverType.INVIDIOUS, endpoint, video, height, title, null, includeSeparateTracks = true)
            },
            check = { candidate, height -> downloads.probe(candidate.media, candidate.auth, height, automatic = true) },
            cooldowns = downloadCooldowns, resolvePeerTube = externalResolver::peerTube,
            checkExact = { candidate, variant -> downloads.probe(candidate.media, candidate.auth, variant.height,
                automatic = true, exactVariant = variant) })
    }
    internal val offlineWork by lazy { OfflineWork(this) }
    internal val offlineModels by lazy { OfflineModels(this, offlineWork) }
    internal val libraryBackup by lazy { LibraryBackup(this, library, offlineWork) }
    lateinit var settings: AppSettings
    lateinit var library: LibraryStore
    lateinit var downloads: DownloadManager
    lateinit var translations: TranslationManager
    lateinit var magic: MagicSearchRepository

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        library = LibraryStore(this)
        downloads = DownloadManager(this, library, downloadCooldowns)
        translations = TranslationManager(this, library, settings, offlineModels, offlineWork)
        magic = MagicSearchRepository()
    }
}
fun android.app.Activity.app(): Video2Application = application as Video2Application
