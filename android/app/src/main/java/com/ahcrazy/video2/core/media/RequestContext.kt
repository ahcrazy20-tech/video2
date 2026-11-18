package com.ahcrazy.video2.core.media

import com.ahcrazy.video2.core.model.DownloadAuth
import okhttp3.HttpUrl

/** Only ordinary browser metadata, never Cookie, Authorization, client hints, tokens or the request body. */
internal data class ObservedMediaRequest(
    val referer: String?, val origin: String?, val userAgent: String?, val accept: String?
) {
    fun authFor(url: HttpUrl, defaultUserAgent: String) = DownloadAuth(
        userAgent ?: defaultUserAgent, referer, origin, useBrowserCookies = true,
        accept = accept, observedResource = url.toString()
    )

    companion object {
        fun from(headers: Map<String, String>): ObservedMediaRequest {
            fun value(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            val origin = value("Origin")
            return ObservedMediaRequest(
                httpMediaUrl(value("Referer"))?.toString(),
                if (origin == "null") origin else httpMediaUrl(origin)?.origin(),
                value("User-Agent").safeHeader(512), value("Accept").safeHeader(1024)
            )
        }
    }
}

/** Hook callbacks and the media bridge can arrive in either order. Keep the exact URL, per navigation. */
internal class MediaRequestContexts(private val limit: Int = 300) {
    private val requests = LinkedHashMap<HttpUrl, ObservedMediaRequest>(32, .75f, true)
    private var revision = 0

    @Synchronized fun reset(newRevision: Int) { revision = newRevision; requests.clear() }

    @Synchronized fun remember(revision: Int, url: HttpUrl, context: ObservedMediaRequest): Boolean {
        if (revision != this.revision || limit <= 0) return false
        val changed = requests.put(url, context) != context
        if (requests.size > limit) requests.remove(requests.entries.first().key)
        return changed
    }

    @Synchronized fun get(url: HttpUrl): ObservedMediaRequest? = requests[url]
}

internal fun String?.safeHeader(limit: Int): String? = this?.trim(' ', '\t')?.takeIf {
    it.length in 1..limit && it.all { c -> c in ' '..'~' }
}

/** When only the page is known, use a conservative referrer, not an invented Origin header. */
internal fun pageReferer(page: HttpUrl?, target: HttpUrl?): String? {
    if (page == null || target == null || page.isHttps && !target.isHttps) return null
    return if (page.origin() == target.origin()) page.toString() else page.origin() + "/"
}
