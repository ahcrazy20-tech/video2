package com.ahcrazy.video2

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.ui.AppTab
import com.ahcrazy.video2.ui.AppTabs
import com.ahcrazy.video2.ui.browser.BrowserScreen
import com.ahcrazy.video2.ui.browser.BrowserSession
import com.ahcrazy.video2.ui.theme.Video2Theme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Video2Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun browserStartsWithoutAWebViewAndBottomTabsOpenOnTouch() {
        val mounted = mutableSetOf<AppTab>()
        compose.setContent {
            var selected by remember { mutableStateOf(AppTab.BROWSER) }
            val browser = remember { BrowserSession() }
            DisposableEffect(browser) { onDispose { browser.destroy() } }
            Video2Theme(rtl = true) {
                AppTabs(selected, "ar", rememberSaveableStateHolder(), { selected = it }) { active ->
                    DisposableEffect(active) {
                        mounted.add(active)
                        onDispose { mounted.remove(active) }
                    }
                    if (active == AppTab.BROWSER) BrowserScreen(true, browser, null, {}, {})
                    else Text("Screen ${active.name}")
                }
            }
        }

        compose.onNodeWithTag("browser.address").assertIsDisplayed()
        compose.onNodeWithTag("browser.start").assertIsDisplayed()
        compose.onNodeWithTag("browser.viewport").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf(AppTab.BROWSER), mounted) }
        AppTab.entries.drop(1).forEach { tab ->
            compose.onNodeWithTag(tab.label).performTouchInput { click() }
            compose.onNodeWithTag(tab.label).assertIsSelected()
            compose.onNodeWithText("Screen ${tab.name}").assertIsDisplayed()
            compose.onNodeWithTag("browser.address").assertDoesNotExist()
            compose.runOnIdle { assertEquals(setOf(tab), mounted) }
        }
        compose.onNodeWithTag(AppTab.BROWSER.label).performTouchInput { click() }
        compose.onNodeWithTag("browser.start").assertIsDisplayed()
        // Robolectric cannot reliably idle a second native dialog window; test the entry point in this window.
        compose.onNodeWithTag("browser.start.direct").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(setOf(AppTab.BROWSER), mounted) }
    }

    @Test fun queuedDownloadAndTabSwitchesKeepTheMountedPageHistoryAndCapture() = MockWebServer().use { server ->
        val clip = byteArrayOf(0, 0, 0, 24) + "ftypisom00000000fixture-bytes".toByteArray()
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(clip)))
        lateinit var session: BrowserSession
        compose.setContent {
            var selected by remember { mutableStateOf(AppTab.BROWSER) }
            val browser = remember { BrowserSession() }
            DisposableEffect(browser) {
                session = browser
                onDispose { browser.destroy() }
            }
            Video2Theme(rtl = true) {
                AppTabs(selected, "ar", rememberSaveableStateHolder(), { selected = it }) { active ->
                    if (active == AppTab.BROWSER) BrowserScreen(true, browser, null, {}, {})
                    else Text("Screen ${active.name}")
                }
            }
        }

        val address = "https://example.invalid/watch?id=7"
        compose.onNodeWithTag("browser.address").performTextInput(address)
        compose.onNodeWithTag("browser.go", useUnmergedTree = true).assertIsEnabled().performTouchInput { click() }
        compose.onNodeWithTag("browser.viewport").assertIsDisplayed()
        compose.onNodeWithTag("browser.address").assertIsDisplayed()
        val page = "https://example.invalid/watch?id=8"
        val media = DetectedMedia(server.url("/download.mp4").toString(), "fixture clip", MediaKind.MP4,
            pageUrl = page, extractionMethod = "webview-download")
        val (view, tabId) = compose.runOnIdle {
            val web = compose.activity.window.decorView.findWebView()!!
            val shadow = shadowOf(web)
            assertEquals(address, shadow.lastLoadedUrl)
            shadow.pushEntryToHistory(address)
            shadow.pushEntryToHistory(page)
            web.webViewClient.doUpdateVisitedHistory(web, page, false)
            web.scrollY = 240
            session.current.ingest(media)
            val job = compose.activity.app().downloads.enqueue(media, session.current.auth(media))
            assertTrue(job.state.isBusy)
            web to session.currentId
        }
        for (other in listOf(AppTab.DOWNLOADS, AppTab.LIBRARY)) {
            compose.onNodeWithTag(other.label).performTouchInput { click() }
            compose.onNodeWithTag("browser.viewport").assertDoesNotExist()
            compose.onNodeWithTag(AppTab.BROWSER.label).performTouchInput { click() }
            compose.onNodeWithTag("browser.viewport").assertIsDisplayed()
            compose.onNodeWithTag("browser.address").assertIsDisplayed()
            compose.runOnIdle {
                assertSame(view, compose.activity.window.decorView.findWebView())
                assertEquals(tabId, session.currentId)
                assertEquals(page, session.current.url)
                assertEquals(page, session.current.address)
                assertTrue(session.current.canGoBack)
                assertEquals(240, view.scrollY)
                assertEquals(media.url, session.current.detected.single().url)
                assertEquals(address, shadowOf(view).lastLoadedUrl)
                assertEquals(0, shadowOf(view).reloadInvocations)
            }
        }
    }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }
}
