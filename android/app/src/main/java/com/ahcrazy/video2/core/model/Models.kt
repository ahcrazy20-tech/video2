package com.ahcrazy.video2.core.model

import java.util.UUID

/** Faithful Android port of iOS Models.swift (MediaKind / DRMKind / media / jobs). */
enum class MediaKind(val ext: String, val titleAr: String, val titleEn: String) {
    MP4("mp4", "MP4", "MP4"), MOV("mov", "MOV", "MOV"), M4V("m4v", "M4V", "M4V"),
    THREE_GP("3gp", "3GP", "3GP"), HLS("m3u8", "HLS (m3u8)", "HLS (m3u8)"),
    DASH("mpd", "DASH", "DASH"), WEBM("webm", "WebM", "WebM"), TS("ts", "جزء TS", "TS segment"),
    AAC("aac", "AAC", "AAC"), MP3("mp3", "MP3", "MP3"), WAV("wav", "WAV", "WAV"),
    MKV("mkv", "MKV", "MKV"), AVI("avi", "AVI", "AVI"), OTHER("bin", "أخرى", "Other");

    val isCompleteVideo: Boolean get() = this in setOf(MP4, MOV, M4V, THREE_GP, HLS, WEBM, MKV, AVI, DASH)
    val isLikelyFragment: Boolean get() = this == TS
    /** ExoPlayer on Android plays everything AVPlayer couldn't (MKV/WebM/DASH included). */
    val exoSupported: Boolean get() = this != OTHER

    companion object {
        fun infer(url: String, mime: String?): MediaKind {
            val u = url.substringBefore("?").lowercase()
            val m = (mime ?: "").lowercase()
            return when {
                u.contains(".m3u8") || m.contains("mpegurl") -> HLS
                u.contains(".mpd") || m.contains("dash") -> DASH
                u.endsWith(".webm") || m.contains("webm") -> WEBM
                u.endsWith(".mkv") || m.contains("matroska") -> MKV
                u.endsWith(".avi") -> AVI
                u.endsWith(".mov") -> MOV
                u.endsWith(".m4v") -> M4V
                u.endsWith(".3gp") || u.endsWith(".3gpp") -> THREE_GP
                u.endsWith(".ts") -> TS
                u.endsWith(".aac") || m.contains("aac") -> AAC
                u.endsWith(".mp3") || (m.contains("mpeg") && m.contains("audio")) -> MP3
                u.endsWith(".wav") -> WAV
                u.endsWith(".mp4") || m.contains("mp4") || m.contains("video") -> MP4
                else -> OTHER
            }
        }
    }
}

enum class DRMKind(val titleAr: String, val titleEn: String) {
    NONE("غير محمي", "Not protected"),
    FAIRPLAY("FairPlay (Apple DRM)", "FairPlay (Apple DRM)"),
    WIDEVINE("Widevine (Google DRM)", "Widevine (Google DRM)"),
    PLAYREADY("PlayReady (Microsoft DRM)", "PlayReady (Microsoft DRM)"),
    ENCRYPTED_HLS("HLS مشفّر بترخيص", "License-encrypted HLS"),
    UNKNOWN_PROTECTED("حماية DRM غير محددة", "Unknown DRM protection");

    val isProtected: Boolean get() = this != NONE
}

data class HlsVariant(
    val url: String, val bandwidth: Int = 0, val width: Int? = null,
    val height: Int? = null, val codecs: String? = null
) {
    val id: String get() = url
    val qualityLabel: String
        get() = when {
            height != null && height > 0 -> "${height}p"
            bandwidth >= 1_000_000 -> String.format("%.1f Mbps", bandwidth / 1_000_000.0)
            else -> "${maxOf(bandwidth, 0) / 1000} kbps"
        }
}

data class DownloadAuth(val userAgent: String, val referer: String? = null, val cookie: String? = null) {
    companion object {
        const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        val default = DownloadAuth(CHROME_UA)
    }
    fun headers(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        if (!referer.isNullOrEmpty()) {
            put("Referer", referer)
            runCatching {
                val u = java.net.URL(referer)
                put("Origin", "${u.protocol}://${u.host}")
            }
        }
        if (!cookie.isNullOrEmpty()) put("Cookie", cookie)
    }
}

data class DetectedMedia(
    val url: String, val title: String, val kind: MediaKind, val mime: String? = null,
    val qualityLabel: String? = null, val drm: DRMKind = DRMKind.NONE, val pageUrl: String? = null,
    val extractionMethod: String = "html5", val durationSec: Double? = null, val byteSize: Long? = null,
    val width: Int? = null, val height: Int? = null, val probed: Boolean = false,
    val variants: List<HlsVariant> = emptyList()
) {
    val id: String get() = url
    val canDownload: Boolean get() = !drm.isProtected && !url.startsWith("blob:") && !url.startsWith("data:")
    val isFragment: Boolean get() = kind.isLikelyFragment || url.lowercase().contains(".m4s")
    val durationText: String
        get() {
            val d = durationSec
            if (d == null || !d.isFinite() || d <= 0) return "—"
            val n = d.toInt()
            return if (n >= 3600) String.format("%d:%02d:%02d", n / 3600, (n % 3600) / 60, n % 60)
            else String.format("%d:%02d", n / 60, n % 60)
        }
}

data class LibraryFolder(val id: String = UUID.randomUUID().toString(), val name: String, val createdAt: Long = System.currentTimeMillis())

data class DubbedInfo(val provider: String, val voice: String, val language: String, val createdAt: Long, val totalDuration: Double)

data class SavedVideo(
    val id: String = UUID.randomUUID().toString(), val title: String, val sourceUrl: String,
    val pageUrl: String? = null, val localPath: String, val thumbnailPath: String? = null,
    val kind: MediaKind = MediaKind.MP4, val createdAt: Long = System.currentTimeMillis(),
    val durationSec: Double? = null, val fileSize: Long = 0L, val lastPositionSec: Double = 0.0,
    val extractionMethod: String = "", val folderId: String? = null,
    val subtitleFiles: Map<String, String> = emptyMap(), val subtitleTargetLang: String? = null,
    val dubbedAudioPath: String? = null, val dubbedInfo: DubbedInfo? = null
) {
    val hasSubtitles: Boolean get() = subtitleFiles.values.any { java.io.File(it).exists() }
    val hasDubbedAudio: Boolean get() = dubbedAudioPath != null && java.io.File(dubbedAudioPath).exists()
    val resumeFraction: Float get() {
        val d = durationSec ?: return 0f
        return if (d > 0) (lastPositionSec / d).toFloat().coerceIn(0f, 1f) else 0f
    }
}

enum class DownloadState { QUEUED, RUNNING, PAUSED, FAILED, COMPLETED, BLOCKED_DRM;
    val isBusy: Boolean get() = this == QUEUED || this == RUNNING
}

data class DownloadJob(
    val id: String = UUID.randomUUID().toString(), val media: DetectedMedia,
    val state: DownloadState = DownloadState.QUEUED, val progress: Double = 0.0,
    val bytesWritten: Long = 0L, val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(), val destPath: String? = null,
    val preferredMaxHeight: Int? = null, val auth: DownloadAuth? = null
)
