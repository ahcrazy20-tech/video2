package com.ahcrazy.video2.core.media

import com.ahcrazy.video2.core.model.MediaKind
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Parse without removing signed query parameters. Fragments are never sent over HTTP. */
internal fun httpMediaUrl(raw: String?): HttpUrl? = raw?.takeIf { it.length in 1..16_384 }
    ?.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
    ?.let { if (it.fragment == null) it else it.newBuilder().fragment(null).build() }

internal fun HttpUrl.origin(): String = newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")

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
internal fun responseMediaKind(url: String, mime: String?, prefix: ByteArray, hint: MediaKind): MediaKind {
    val text = prefix.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
    val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (text.startsWith("#EXTM3U")) return MediaKind.HLS
    if (type == "application/dash+xml" || Regex("<([\\w-]+:)?MPD(?:\\s|>)").containsMatchIn(text)) return MediaKind.DASH
    if (type in setOf("text/html", "application/xhtml+xml", "application/json") ||
        text.startsWith("<!doctype html", true) || text.startsWith("<html", true) ||
        text.startsWith('{') || text.startsWith('[')) {
        throw MediaDownloadException("الرابط رجّع صفحة أو طلب تسجيل دخول، مش ملف فيديو.")
    }
    val fromMime = MediaKind.infer("", mime)
    if (fromMime != MediaKind.OTHER) return fromMime
    val fromUrl = MediaKind.infer(url, null)
    if (fromUrl != MediaKind.OTHER) return fromUrl
    if (hint != MediaKind.OTHER) return hint
    if (prefix.size >= 12 && prefix.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") return MediaKind.MP4
    if (prefix.size >= 4) {
        if (text.startsWith("OggS")) return MediaKind.OGG
        if (text.startsWith("ID3")) return MediaKind.MP3
        if (text.startsWith("fLaC")) return MediaKind.FLAC
        if (prefix[0] == 0x1a.toByte() && prefix[1] == 0x45.toByte() && prefix[2] == 0xdf.toByte() && prefix[3] == 0xa3.toByte()) return MediaKind.WEBM
    }
    throw MediaDownloadException("الرد مش ملف فيديو أو صوت معروف. افتح الصفحة وشغّل الفيديو أولًا.")
}

internal class MediaDownloadException(message: String) : java.io.IOException(message)
