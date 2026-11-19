package com.ahcrazy.video2.core.media

import okhttp3.HttpUrl
import java.util.Locale

internal enum class BrowserAdMode {
    OFF, BALANCED, STRICT;

    companion object {
        fun fromPreferences(enabled: Boolean, stored: String?) = when {
            !enabled -> OFF
            stored == "strict" -> STRICT
            else -> BALANCED
        }
    }
}

/** Offline, deliberately narrow rules. They only apply to the embedded browser, not playback/downloads. */
internal class BrowserAdRules(base: Iterable<String>, strict: Iterable<String>) {
    private val baseHosts = base.toHosts()
    private val allHosts = baseHosts + strict.toHosts()

    // App-initiated address loads don't call shouldOverrideUrlLoading. In-page links and redirects do.
    fun blocksNavigation(target: HttpUrl, mode: BrowserAdMode): Boolean = when (mode) {
        BrowserAdMode.OFF -> false
        BrowserAdMode.BALANCED -> target.host.matches(baseHosts)
        BrowserAdMode.STRICT -> target.host.matches(allHosts)
    }

    fun blocksRequest(target: HttpUrl, mainFrame: Boolean, mode: BrowserAdMode): Boolean {
        if (mainFrame || mode == BrowserAdMode.OFF) return false
        if (blocksNavigation(target, mode)) return true
        return mode == BrowserAdMode.STRICT && isAdEndpoint(target)
    }

    /** Classify detected sources separately from the browser's on/off switch. */
    fun isAdSource(target: HttpUrl): Boolean = target.host.matches(allHosts) || isAdEndpoint(target)

    private fun isAdEndpoint(url: HttpUrl): Boolean {
        val path = url.encodedPath
        if (url.pathSegments.last().lowercase(Locale.ROOT) in adFiles) return true
        return url.host.matches(youtubeHosts) && (path.startsWith("/pagead/") || path == "/api/stats/ads")
    }

    private fun String.matches(hosts: Set<String>): Boolean {
        var suffix = trimEnd('.')
        while (suffix.isNotEmpty()) {
            if (suffix in hosts) return true
            suffix = suffix.substringAfter('.', "")
        }
        return false
    }

    private fun Iterable<String>.toHosts(): Set<String> = mapNotNull { line ->
        line.substringBefore('#').trim().trimEnd('.').lowercase(Locale.ROOT).takeIf(hostPattern::matches)
    }.toSet()

    private companion object {
        val hostPattern = Regex("(?:[a-z0-9-]+\\.)+[a-z0-9-]+")
        val youtubeHosts = setOf("youtube.com", "youtube-nocookie.com")
        val adFiles = setOf("vast.xml", "vmap.xml", "preroll.vast", "adtag.xml")
    }
}
