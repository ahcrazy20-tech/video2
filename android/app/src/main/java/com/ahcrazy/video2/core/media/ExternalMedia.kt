package com.ahcrazy.video2.core.media

import com.ahcrazy.video2.core.model.DetectedMedia
import okhttp3.Dns
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.UnknownHostException

/** External resolvers receive a public watch ID, never a captured playback URL. */
internal data class YouTubeVideo private constructor(val id: String) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$id"

    companion object {
        private val idPattern = Regex("[A-Za-z0-9_-]{11}")
        fun fromId(raw: String?) = raw?.takeIf(idPattern::matches)?.let(::YouTubeVideo)

        fun parse(raw: String?): YouTubeVideo? {
            val url = httpMediaUrl(raw?.trim()) ?: return null
            if (!url.isYouTubePage()) return null
            val path = url.pathSegments.dropLastWhile { it.isEmpty() }
            return when {
                url.host == "youtu.be" -> fromId(path.singleOrNull())
                path == listOf("watch") -> fromId(url.queryParameter("v"))
                path.size == 2 && path[0] in setOf("embed", "shorts", "live", "v") -> fromId(path[1])
                else -> null
            }
        }

        fun fromMedia(media: DetectedMedia): YouTubeVideo? =
            parse(media.publicPageUrl) ?: parse(media.requestReferer) ?: parse(media.pageUrl)
    }
}

/** Explicit public HTTPS endpoints only. Do not follow a resolver response into private device services. */
internal fun externalHttpsUrl(raw: String?): HttpUrl? = httpMediaUrl(raw)?.takeIf { it.isHttps && publicHostname(it.host) }

internal fun resolverEndpoint(raw: String): HttpUrl? = externalHttpsUrl(raw.trim())?.takeIf {
    it.encodedQuery == null && '#' !in raw && it.toString().length <= 300
}

private fun publicHostname(host: String): Boolean {
    val name = host.trimEnd('.').lowercase()
    return '.' in name && ':' !in name && name.any { it in 'a'..'z' } &&
        !listOf("localhost", "local", "internal", "lan", "onion", "home.arpa").any { name == it || name.endsWith(".$it") }
}

internal val publicMediaDns = Dns { hostname ->
    if (!publicHostname(hostname)) throw UnknownHostException("Not a public media host")
    Dns.SYSTEM.lookup(hostname).also { addresses ->
        if (addresses.isEmpty() || addresses.any { !isPublicMediaAddress(it) }) throw UnknownHostException("Not a public media host")
    }
}

internal fun isPublicMediaAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 0xff }
    if (bytes.size == 16) return bytes[0] in 0x20..0x3f && bytes.take(4) != listOf(0x20, 0x01, 0x0d, 0xb8)
    if (bytes.size != 4) return false
    val (a, b, c) = bytes
    return a in 1..223 && a != 127 && !(a == 100 && b in 64..127) && !(a == 198 && b in 18..19) &&
        !(a == 192 && b == 0 && c in setOf(0, 2)) && !(a == 198 && b == 51 && c == 100) && !(a == 203 && b == 0 && c == 113)
}
