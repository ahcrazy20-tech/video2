package com.ahcrazy.video2.core.model

import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.isMediaFragment
import com.ahcrazy.video2.core.media.origin
import com.ahcrazy.video2.core.media.manifestKind
import com.ahcrazy.video2.core.media.safeHeader
import okhttp3.HttpUrl
import java.util.UUID

/** Faithful Android port of iOS Models.swift (MediaKind / DRMKind / media / jobs). */
enum class MediaKind(val ext: String, val titleAr: String, val titleEn: String) {
    MP4("mp4", "MP4", "MP4"), MOV("mov", "MOV", "MOV"), M4V("m4v", "M4V", "M4V"),
    THREE_GP("3gp", "3GP", "3GP"), HLS("m3u8", "HLS (m3u8)", "HLS (m3u8)"),
    DASH("mpd", "DASH", "DASH"), WEBM("webm", "WebM", "WebM"), TS("ts", "جزء TS", "TS segment"),
    AAC("aac", "AAC", "AAC"), MP3("mp3", "MP3", "MP3"), WAV("wav", "WAV", "WAV"),
    MKV("mkv", "MKV", "MKV"), AVI("avi", "AVI", "AVI"), M4A("m4a", "M4A", "M4A"),
    OGG("ogg", "OGG", "OGG"), FLAC("flac", "FLAC", "FLAC"), OTHER("bin", "أخرى", "Other");

    val isCompleteVideo: Boolean get() = this in setOf(MP4, MOV, M4V, THREE_GP, HLS, WEBM, MKV, AVI, DASH)
    val isLikelyFragment: Boolean get() = this == TS
    /** ExoPlayer on Android plays everything AVPlayer couldn't (MKV/WebM/DASH included). */
    val exoSupported: Boolean get() = this != OTHER

    companion object {
        fun infer(url: String, mime: String?): MediaKind {
            val parsed = httpMediaUrl(url)
            val path = (parsed?.encodedPath ?: url.substringBefore('?').substringBefore('#')).lowercase()
            val type = (mime?.takeIf { it.isNotBlank() } ?: parsed?.queryParameter("mime"))
                .orEmpty().substringBefore(';').trim().lowercase()
            val manifest = parsed?.manifestKind()
            return when {
                type.contains("mpegurl") || manifest == HLS -> HLS
                type == "application/dash+xml" || manifest == DASH -> DASH
                path.endsWith(".m3u8") -> HLS
                path.endsWith(".mpd") -> DASH
                path.endsWith(".webm") || type.endsWith("/webm") -> WEBM
                path.endsWith(".mkv") || type.contains("matroska") -> MKV
                path.endsWith(".avi") || type == "video/x-msvideo" -> AVI
                path.endsWith(".mov") || type == "video/quicktime" -> MOV
                path.endsWith(".m4v") -> M4V
                path.endsWith(".3gp") || path.endsWith(".3gpp") || type.endsWith("/3gpp") -> THREE_GP
                path.endsWith(".ts") || type == "video/mp2t" -> TS
                path.endsWith(".aac") || type == "audio/aac" -> AAC
                path.endsWith(".mp3") || type == "audio/mpeg" -> MP3
                path.endsWith(".wav") || type in setOf("audio/wav", "audio/x-wav") -> WAV
                path.endsWith(".m4a") || type == "audio/mp4" -> M4A
                path.endsWith(".ogg") || path.endsWith(".opus") || type.endsWith("/ogg") -> OGG
                path.endsWith(".flac") || type == "audio/flac" -> FLAC
                path.endsWith(".mp4") || type == "video/mp4" -> MP4
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
    val height: Int? = null, val codecs: String? = null, val audioGroup: String? = null
) {
    val id: String get() = url
    val qualityLabel: String
        get() = when {
            height != null && height > 0 -> "${height}p"
            bandwidth >= 1_000_000 -> String.format("%.1f Mbps", bandwidth / 1_000_000.0)
            else -> "${maxOf(bandwidth, 0) / 1000} kbps"
        }
}

data class DownloadAuth(
    val userAgent: String,
    val referer: String? = null,
    val origin: String? = null,
    val useBrowserCookies: Boolean = false,
    val accept: String? = null,
    val observedResource: String? = null
) {
    companion object {
        const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        val default = DownloadAuth(CHROME_UA)
    }

    internal fun headersFor(target: HttpUrl, initial: HttpUrl): Map<String, String> = buildMap {
        put("User-Agent", userAgent.safeHeader(512) ?: CHROME_UA)
        val ref = httpMediaUrl(referer)
        if (!target.isHttps && (ref?.isHttps == true || initial.isHttps)) return@buildMap
        val recorded = httpMediaUrl(observedResource)
        val crossOrigin = target.origin() != initial.origin() || (recorded != null && target.origin() != recorded.origin())
        ref?.let { put("Referer", if (crossOrigin) it.origin() + "/" else it.toString()) }
        if (!crossOrigin && recorded != null) {
            if (origin == "null") put("Origin", "null")
            else httpMediaUrl(origin)?.let { put("Origin", it.origin()) }
            if (target == recorded) accept.safeHeader(1024)?.let { put("Accept", it) }
        }
    }
}

enum class MediaProblem(val ar: String, val en: String, val needsNewLink: Boolean = false) {
    ACCESS("الموقع رفض طلب التحميل للرابط ده. تشغيل الفيديو مش معناه إن الموقع أتاح حفظه. افتح المصدر لفحص الروابط المتاحة.",
        "The site refused to download this link. Playback does not imply download access. Reopen the source to check available links.", true),
    SIGN_IN("المصدر طلب تسجيل دخول. افتح نفس الصفحة وسجّل الدخول لو عندك صلاحية.",
        "The source requests sign-in. Reopen the page and sign in if you have access.", true),
    EXPIRED("الرابط انتهى أو الملف اتشال. افتح صفحة المصدر والتقط رابط جديد.",
        "The link expired or the file is gone. Reopen the source page for a fresh link.", true),
    NOT_MEDIA("ده رابط صفحة أو جزء بث، مش ملف وسائط كامل.", "This is a page or a stream fragment, not a complete media source.", true),
    TEMPORARY("المصدر مش متاح مؤقتًا. حاول تاني بعد شوية.", "The source is temporarily unavailable. Try again shortly."),
    NETWORK("الاتصال بالمصدر ما اكتملش. اتأكد من الإنترنت وحاول تاني.", "Couldn't connect to the source. Check your connection and try again."),
    BAD_RESPONSE("الخادم رجّع بيانات ناقصة أو صيغة غير مدعومة.", "The server returned incomplete or unsupported data.");

    fun text(arabic: Boolean) = if (arabic) ar else en
}

data class DetectedMedia(
    val url: String, val title: String, val kind: MediaKind, val mime: String? = null,
    val qualityLabel: String? = null, val drm: DRMKind = DRMKind.NONE, val pageUrl: String? = null,
    val extractionMethod: String = "html5", val durationSec: Double? = null, val byteSize: Long? = null,
    val width: Int? = null, val height: Int? = null, val probed: Boolean = false,
    val variants: List<HlsVariant> = emptyList(),
    val requestReferer: String? = null,
    val fromAd: Boolean = false, val probeProblem: MediaProblem? = null,
    val checkedAtMs: Long? = null, val probeHttpStatus: Int? = null
) {
    val id: String get() = url
    fun downloadChecked(now: Long = System.currentTimeMillis()): Boolean =
        probeProblem == null && (extractionMethod == "webview-download" || checkedAtMs?.let { now - it in 0..90_000 } == true)
    val canPlay: Boolean get() = kind.exoSupported && !drm.isProtected && !isFragment && probeProblem?.needsNewLink != true
    val canDownload: Boolean get() = probeProblem?.needsNewLink != true && !drm.isProtected && !isFragment && kind != MediaKind.DASH && httpMediaUrl(url) != null
    val isFragment: Boolean get() = kind.isLikelyFragment || httpMediaUrl(url)?.isMediaFragment() == true
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
    val preferredMaxHeight: Int? = null, val auth: DownloadAuth? = null,
    val preferredVariantUrl: String? = null, val problem: MediaProblem? = null, val httpStatus: Int? = null
) {
    val canRetry: Boolean get() = media.canDownload && problem?.needsNewLink != true && state in setOf(DownloadState.PAUSED, DownloadState.FAILED)
}
