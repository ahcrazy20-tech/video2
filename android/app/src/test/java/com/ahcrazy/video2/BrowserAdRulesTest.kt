package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.BrowserAdMode
import com.ahcrazy.video2.core.media.BrowserAdMode.*
import com.ahcrazy.video2.core.media.BrowserAdRules
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAdRulesTest {
    private val rules = BrowserAdRules(listOf(" # comment", "DoubleClick.NET.  # notes", "popads.net"),
        listOf("doubleverify.com", "https://cdn.example.org"))

    @Test fun savedModeIsUsedAndDisabledWins() {
        assertEquals(BALANCED, BrowserAdMode.fromPreferences(true, null))
        assertEquals(BALANCED, BrowserAdMode.fromPreferences(true, "unknown"))
        assertEquals(STRICT, BrowserAdMode.fromPreferences(true, "strict"))
        assertEquals(OFF, BrowserAdMode.fromPreferences(false, "strict"))
    }

    @Test fun balancedBlocksAdRequestsAndInPageNavigationsWithoutHijackingTypedPages() {
        val ad = "https://Serve.DouBleClick.NET./frame.html".toHttpUrl()
        assertTrue(rules.blocksRequest(ad, false, BALANCED))
        assertTrue(rules.blocksNavigation(ad, BALANCED))
        // Typed addresses go through WebView.loadUrl, not shouldOverrideUrlLoading.
        assertFalse(rules.blocksRequest(ad, true, BALANCED))
        assertFalse(rules.blocksNavigation("https://notdoubleclick.net/watch".toHttpUrl(), BALANCED))
        assertFalse(rules.blocksNavigation("https://doubleclick.net.example.org/".toHttpUrl(), BALANCED))
        assertFalse(rules.blocksNavigation(ad, OFF))
        assertFalse(rules.blocksRequest(ad, false, OFF))
        assertTrue(rules.isAdSource(ad)) // Capture filtering remains independent.
    }

    @Test fun strictAddsRulesWithoutBlockingNormalVideoAndLoginPaths() {
        for (url in listOf("https://cdn.doubleverify.com/pixel", "https://watch.example.org/publisher/vast.xml",
            "https://www.youtube.com/pagead/viewthroughconversion", "https://m.youtube.com/api/stats/ads")) {
            val target = url.toHttpUrl()
            assertTrue(url, rules.blocksRequest(target, false, STRICT))
            assertFalse(url, rules.blocksRequest(target, false, BALANCED))
        }
        for (url in listOf("https://accounts.google.com/o/oauth2/auth", "https://www.youtube.com/youtubei/v1/player",
            "https://r1.googlevideo.com/videoplayback?expire=123&range=0-200", "https://cdn.example.org/movie/index.m3u8?sig=abc",
            "https://watch.example.org/videos/vast.xml/lesson.mp4", "https://watch.example.org/movie.mp4?adformat=signed",
            "https://cdn.example.org/v1/player.js")) {
            val target = url.toHttpUrl()
            assertFalse(url, rules.blocksRequest(target, false, STRICT))
            assertFalse(url, rules.blocksNavigation(target, STRICT))
        }
    }
}
