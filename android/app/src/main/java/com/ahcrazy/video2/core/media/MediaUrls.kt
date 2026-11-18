package com.ahcrazy.video2.core.media

import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Parse without removing signed query parameters. Fragments are never sent over HTTP. */
internal fun httpMediaUrl(raw: String?): HttpUrl? = raw?.takeIf { it.length in 1..16_384 }
    ?.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
    ?.let { if (it.fragment == null) it else it.newBuilder().fragment(null).build() }

internal fun HttpUrl.origin(): String = newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")

internal fun HttpUrl.isPlayerTrackDelivery(): Boolean =
    (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) && pathSegments.last().equals("videoplayback", true)

internal fun HttpUrl.isYouTubePage(): Boolean = host == "youtu.be" || host == "youtube.com" ||
    host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")

internal fun HttpUrl.manifestKind(): MediaKind? {
    if (!isYouTubePage() && host != "googlevideo.com" && !host.endsWith(".googlevideo.com")) return null
    val segment = pathSegments.getOrNull(pathSegments.indexOf("manifest").takeIf { it >= 0 }?.plus(1) ?: return null)
    return when (segment) {
        "hls_playlist", "hls_variant" -> MediaKind.HLS
        "dash" -> MediaKind.DASH
        else -> null
    }
}

/** Google Video's explicit Unix expiry can be diagnosed without altering the signed URL. */
internal fun HttpUrl.isExpiredPlayerLink(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
    if (host != "googlevideo.com" && !host.endsWith(".googlevideo.com")) return false
    val pathExpiry = pathSegments.indexOf("expire").takeIf { it >= 0 }?.let { pathSegments.getOrNull(it + 1) }
    val expires = (queryParameter("expire") ?: pathExpiry)?.toLongOrNull() ?: return false
    return expires in 946684800..nowEpochSeconds
}

internal fun HttpUrl.isMediaFragment(): Boolean {
    val leaf = pathSegments.last().lowercase()
    return leaf.substringAfterLast('.', "") in setOf("ts", "m4s", "cmfv", "cmfa", "vtt", "srt") ||
        (leaf == "videoplayback" && (queryParameter("range") != null || queryParameter("sq") != null))
}

internal fun HttpUrl.isVideoAd(): Boolean = pathSegments.any { it.lowercase().trimEnd('/') in adMarkers } ||
    queryParameterNames.any { it.lowercase() in adMarkers }

private val adMarkers = setOf("preroll", "midroll", "postroll", "vast", "vpaid", "vmap", "adformat", "ad_type", "ad_unit", "ad_tag", "adtag", "ad_break", "videoads")

internal data class ByteWindow(val offset: Long, val length: Long) {
    init { require(offset >= 0 && length > 0 && offset <= Long.MAX_VALUE - length) }
    val last: Long get() = offset + length - 1
    val header: String get() = "bytes=$offset-$last"
}

internal data class ContentRange(val start: Long, val end: Long, val total: Long?) {
    val length: Long get() = end - start + 1

    companion object {
        private val pattern = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
        fun parse(value: String?): ContentRange? {
            val match = pattern.matchEntire(value?.trim().orEmpty()) ?: return null
            val (a, b, c) = match.destructured
            val start = a.toLongOrNull() ?: return null
            val end = b.toLongOrNull()?.takeIf { it >= start && it < Long.MAX_VALUE } ?: return null
            val total = if (c == "*") null else c.toLongOrNull()?.takeIf { it > end } ?: return null
            return ContentRange(start, end, total)
        }
    }
}

/** Use MIME or a short signature when a CDN doesn't name the file. Never save a login page. */
internal fun responseMediaKind(url: String, mime: String?, prefix: ByteArray, hint: MediaKind, requireEvidence: Boolean = false): MediaKind {
    val text = prefix.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
    val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (text.startsWith("#EXTM3U")) return MediaKind.HLS
    if (type == "application/dash+xml" || DASH_ROOT.containsMatchIn(text)) return MediaKind.DASH
    if (type in setOf("text/html", "application/xhtml+xml", "application/json") ||
        text.startsWith("<!doctype html", true) || text.startsWith("<html", true) ||
        text.startsWith("<head", true) || text.startsWith("<body", true) ||
        text.startsWith('{') || text.startsWith('[') || type.startsWith("text/")) {
        throw MediaDownloadException("الرابط رجّع صفحة أو طلب تسجيل دخول، مش ملف فيديو.", MediaProblem.NOT_MEDIA)
    }
    val fromMime = MediaKind.infer("", mime)
    if (fromMime != MediaKind.OTHER) return fromMime
    if (prefix.size >= 12 && prefix.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
        return hint.takeIf { it in setOf(MediaKind.MOV, MediaKind.M4A, MediaKind.M4V, MediaKind.THREE_GP) } ?: MediaKind.MP4
    }
    if (prefix.size >= 4) {
        if (text.startsWith("OggS")) return MediaKind.OGG
        if (text.startsWith("ID3")) return MediaKind.MP3
        if (text.startsWith("fLaC")) return MediaKind.FLAC
        if (prefix[0] == 0x1a.toByte() && prefix[1] == 0x45.toByte() && prefix[2] == 0xdf.toByte() && prefix[3] == 0xa3.toByte()) return MediaKind.WEBM
    }
    if (prefix.size >= 12 && text.startsWith("RIFF")) {
        when (String(prefix, 8, 4, Charsets.US_ASCII)) {
            "WAVE" -> return MediaKind.WAV
            "AVI " -> return MediaKind.AVI
        }
    }
    if (prefix.size >= 3 && prefix[0] == 0xff.toByte()) {
        val b = prefix[1].toInt() and 0xff
        if (b and 0xf6 == 0xf0) return MediaKind.AAC
        if (b and 0xe0 == 0xe0 && b and 0x18 != 0x08 && b and 0x06 != 0) return MediaKind.MP3
    }
    if (!requireEvidence) {
        val fromUrl = MediaKind.infer(url, null)
        if (fromUrl != MediaKind.OTHER) return fromUrl
        if (hint != MediaKind.OTHER) return hint
    }
    throw MediaDownloadException("الرد مش ملف فيديو أو صوت معروف. افتح الصفحة وشغّل الفيديو أولًا.", MediaProblem.NOT_MEDIA)
}

internal val DASH_ROOT = Regex("<([\\w-]+:)?MPD(?:\\s|>)")

internal fun ContentRange.matchesResume(start: Long, expectedTotal: Long?) = this.start == start && total != null &&
    (expectedTotal == null || expectedTotal == total)

internal class MediaDownloadException(
    message: String, val problem: MediaProblem = MediaProblem.BAD_RESPONSE,
    val status: Int? = null, val retryDelayMillis: Long? = null, val sourceHost: String? = null,
    val retryAtMs: Long? = null
) : java.io.IOException(message)
