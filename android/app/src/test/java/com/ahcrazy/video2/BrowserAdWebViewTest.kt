package com.ahcrazy.video2

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import com.ahcrazy.video2.core.media.BrowserAdMode
import com.ahcrazy.video2.core.media.BrowserAdRules
import com.ahcrazy.video2.ui.browser.BrowserTab
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserAdWebViewTest {
    @Test fun retainedWebViewUsesTheNewModeAndTabPauseForActualCallbacks() {
        val tab = BrowserTab()
        val box = FrameLayout(RuntimeEnvironment.getApplication())
        val rules = BrowserAdRules(listOf("ads.example.com"), listOf("strict.example.com"))
        try {
            assertTrue(tab.open("https://publisher.example.org/video"))
            tab.mount(box, "", rules, "", BrowserAdMode.BALANCED)
            val view = box.getChildAt(0) as WebView
            val ad = request("https://ads.example.com/click", main = true)
            val strictOnly = request("https://strict.example.com/banner.png")
            val media = request("https://cdn.example.com/stream.m3u8?token=abc")
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, ad))
            assertNull(view.webViewClient.shouldInterceptRequest(view, strictOnly))

            tab.mount(box, "", rules, "", BrowserAdMode.STRICT)
            assertSame(view, box.getChildAt(0))
            assertEquals(204, view.webViewClient.shouldInterceptRequest(view, strictOnly)?.statusCode)
            assertNull(view.webViewClient.shouldInterceptRequest(view, media))

            tab.adBlockPaused = true
            tab.mount(box, "", rules, "", BrowserAdMode.STRICT)
            assertFalse(view.webViewClient.shouldOverrideUrlLoading(view, ad))
            assertNull(view.webViewClient.shouldInterceptRequest(view, strictOnly))
            tab.adBlockPaused = false
            tab.mount(box, "", rules, "", BrowserAdMode.STRICT)
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, ad))
        } finally { tab.destroy() }
    }

    private fun request(url: String, main: Boolean = false): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
