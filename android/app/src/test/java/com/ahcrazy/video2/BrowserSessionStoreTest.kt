package com.ahcrazy.video2

import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.ui.browser.BrowserSession
import com.ahcrazy.video2.ui.browser.BrowserSessionStore
import com.ahcrazy.video2.ui.browser.SAVED_BROWSER_TABS
import com.ahcrazy.video2.ui.browser.savedBrowserPage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserSessionStoreTest {
    @Test fun restoresSelectedPageAndOtherTabsWithoutCapturesOrDraftAddresses() {
        val store = BrowserSessionStore(RuntimeEnvironment.getApplication())
        val original = BrowserSession()
        val first = original.current
        first.open("https://publisher.example.org/watch?id=7")
        original.add()
        val second = original.current
        second.open("https://example.com/chapters/3?episode=12")
        second.adBlockPaused = true
        second.address = "not submitted"
        second.ingest(DetectedMedia("https://cdn.example.com/ep.mp4?token=temporary", "episode", MediaKind.MP4))
        store.save(original.pages())
        original.destroy()

        val restored = BrowserSession(store.load())
        try {
            assertEquals(listOf(first.id, second.id), restored.tabs.map { it.id })
            assertEquals(first.url, restored.tabs.first().url)
            assertEquals(second.id, restored.currentId)
            assertEquals(second.url, restored.current.url)
            assertEquals(second.url, restored.current.address)
            assertTrue(restored.current.adBlockPaused)
            assertTrue(restored.current.detected.isEmpty())
            assertFalse(restored.current.canGoBack)
        } finally { restored.destroy() }
    }

    @Test fun mediaAndSensitiveUrlDoNotReplaceTheRestorablePageButHomeDoes() {
        val store = BrowserSessionStore(RuntimeEnvironment.getApplication())
        val session = BrowserSession()
        try {
            val page = "https://example.com/watch?v=42&episode=5"
            session.current.open(page)
            session.current.open("https://cdn.example.com/content.m3u8?sig=123")
            session.current.open("https://example.com/login?%61ccess_token=abc")
            store.save(session.pages())
            assertEquals(page, store.load()!!.tabs.single().url)
            session.current.goHome()
            store.save(session.pages())
            assertEquals("", store.load()!!.tabs.single().url)
        } finally { session.destroy() }
        for (url in listOf("blob:https://example.com/xyz", "file:///tmp/movie", "https://user:secret@example.com/watch",
            "https://example.com/clip.mp4", "https://example.com/watch?X-Amz-Signature=123", "https://example.com/${"a".repeat(5000)}")) {
            assertEquals("", savedBrowserPage(url))
        }
        assertEquals("https://example.com/watch?id=7", savedBrowserPage("https://example.com/watch?id=7#access_token=abc"))
    }

    @Test fun boundedSnapshotAlwaysKeepsTheSelectedTabAndSourceRecoveryDoesNotClobberOthers() {
        val session = BrowserSession()
        try {
            val first = session.current
            first.open("https://example.com/first")
            repeat(SAVED_BROWSER_TABS + 2) { n ->
                session.add(); session.current.open("https://example.com/watch?id=$n")
            }
            session.currentId = first.id
            val pages = session.pages()
            assertEquals(first.id, pages.selectedId)
            assertEquals(SAVED_BROWSER_TABS, pages.tabs.size)
            assertEquals(first.url, pages.tabs.first().url)
            val tab = session.reopenSource("https://example.com/watch?id=3")!!
            val count = session.tabs.size
            assertSame(first, session.reopenSource(first.url))
            assertSame(tab, session.reopenSource(tab.url))
            assertEquals(count, session.tabs.size)
            val newPage = "https://example.com/another-source"
            val newTab = session.reopenSource(newPage)!!
            assertNotSame(tab, newTab)
            assertEquals(newPage, session.current.url)
            assertEquals(count + 1, session.tabs.size)
            assertEquals("https://example.com/watch?id=3", tab.url)
            assertEquals("https://example.com/first", first.url)
        } finally { session.destroy() }
    }
}
