package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.ContentRange
import com.ahcrazy.video2.core.media.MediaDownloadException
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.isPlayerTrackDelivery
import com.ahcrazy.video2.core.media.matchesResume
import com.ahcrazy.video2.core.media.MediaRequestContexts
import com.ahcrazy.video2.core.media.ObservedMediaRequest
import com.ahcrazy.video2.core.media.isExpiredPlayerLink
import com.ahcrazy.video2.core.media.pageReferer
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.data.Hls
import com.ahcrazy.video2.data.HlsDocument
import com.ahcrazy.video2.data.ProtectedMedia
import com.ahcrazy.video2.core.media.responseMediaKind
import com.ahcrazy.video2.ui.browser.browserTarget
import com.ahcrazy.video2.ui.browser.captureGroups
import com.ahcrazy.video2.ui.browser.captureMessage
import com.ahcrazy.video2.ui.browser.PlaybackHint
import org.junit.Assert.*
import org.junit.Test

class MediaPipelineTests {
    @Test fun incompletePlayerTrafficIsNotTreatedAsAnAvailableSource() {
        val page = "https://m.youtube.com/watch?v=example"
        val message = captureMessage(true, page, PlaybackHint.SEGMENTED, protected = false)
        assertTrue(message.youtube)
        assertEquals("اتعرفنا على المشغّل", message.title)
        assertNotEquals(message.title, captureMessage(true, page, PlaybackHint.SEGMENTED, protected = true).title)
        assertTrue(httpMediaUrl("https://edge.googlevideo.com/videoplayback?mime=video%2Fmp4")!!.isPlayerTrackDelivery())
        assertFalse(httpMediaUrl("https://googlevideo.com.example/videoplayback")!!.isPlayerTrackDelivery())
        assertEquals(MediaKind.HLS, MediaKind.infer("https://manifest.googlevideo.com/api/manifest/hls_playlist/id/sample", null))
    }

    @Test fun radarRanksVerifiedAndDownloadableSourcesAcrossDifferentCdnHosts() {
        val page = "https://example.net/watch?id=7"
        val hls = DetectedMedia("https://first.example/test.m3u8", "sample", MediaKind.HLS, probed = true, pageUrl = page)
        val dash = hls.copy(url = "https://second.example/test.mpd", kind = MediaKind.DASH)
        val old = hls.copy(url = "https://first.example/old.m3u8", pageUrl = "https://example.net/watch?id=6")
        assertEquals(listOf(listOf(hls, dash), listOf(old)), captureGroups(listOf(hls, dash, old)).map { it.items })
    }

    @Test fun addressBarAcceptsDomainsAndSearchButRejectsNonBrowserSchemes() {
        assertEquals("https://example.net/watch?x=1", browserTarget(" example.net/watch?x=1 "))
        assertEquals("https://www.google.com/search?q=video+sample", browserTarget("video sample"))
        assertNull(browserTarget("   "))
        assertNull(browserTarget("file:///test.html"))
        assertNull(browserTarget("https://user:password@example.net"))
    }

    @Test fun signedExtensionlessUrlsAndSafetyRules() {
        val url = "https://media.example/get?id=1&mime=audio%2Fmp4&sig=a%2Bb#ignored"
        assertEquals(MediaKind.M4A, MediaKind.infer(url, null))
        assertEquals("id=1&mime=audio%2Fmp4&sig=a%2Bb", httpMediaUrl(url)!!.encodedQuery)
        assertEquals(MediaKind.HLS, MediaKind.infer("https://media.example/render", "application/vnd.apple.mpegurl; charset=utf-8"))
        assertFalse(DetectedMedia("https://media.example/part.cmfv?sig=x", "part", MediaKind.OTHER).canDownload)
        assertFalse(DetectedMedia("https://media.example/v.mpd", "DASH", MediaKind.DASH).canDownload)
        assertNull(httpMediaUrl("file:///etc/sample.mp4"))
        assertNull(httpMediaUrl("https://username@media.example/get"))
    }

    @Test fun responseSniffRejectsLoginPagesAndFindsUntypedManifests() {
        val url = "https://media.example/render"
        assertEquals(MediaKind.HLS, responseMediaKind(url, "text/plain", "\uFEFF#EXTM3U\n".toByteArray(), MediaKind.OTHER))
        assertEquals(MediaKind.MP4, responseMediaKind(url, "application/octet-stream", byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray(), MediaKind.OTHER))
        assertThrows(MediaDownloadException::class.java) {
            responseMediaKind("$url.mp4", "text/html", "<!DOCTYPE html>".toByteArray(), MediaKind.MP4)
        }
    }

    @Test fun requestHeadersMatchOnlyTheResourceObservedInThisNavigation() {
        val ref = "https://example.net:9443/embed?id=2"
        val asset = httpMediaUrl("https://cdn.example/get?sig=b%2Ba")!!
        val redirect = httpMediaUrl("https://other.example/file")!!
        val observed = ObservedMediaRequest.from(mapOf("referer" to ref, "Origin" to "https://example.net:9443",
            "User-Agent" to "WebView test", "Accept" to "video/mp4", "Cookie" to "never copy", "Authorization" to "never copy"))
        val cache = MediaRequestContexts(limit = 2)
        cache.reset(7)
        assertTrue(cache.remember(7, asset, observed))
        assertFalse(cache.remember(7, asset, observed))
        assertNull(cache.get(httpMediaUrl("https://cdn.example/get?sig=b%2Bb")!!))
        val auth = cache.get(asset)!!.authFor(asset, "fallback")
        val headers = auth.headersFor(asset, asset)
        assertEquals(mapOf("User-Agent" to "WebView test", "Referer" to ref, "Origin" to "https://example.net:9443", "Accept" to "video/mp4"), headers)
        assertEquals(mapOf("User-Agent" to "WebView test", "Referer" to "https://example.net:9443/"), auth.headersFor(redirect, asset))
        assertEquals(mapOf("User-Agent" to "WebView test"), auth.headersFor(httpMediaUrl("http://other.example/file")!!, asset))
        cache.reset(8)
        assertFalse(cache.remember(7, asset, observed))
        assertNull(cache.get(asset))
    }

    @Test fun fallbackReferrerNeverInventsAnOriginOrLeaksACrossSiteQuery() {
        val page = httpMediaUrl("https://example.net:9443/embed?session=secret")!!
        val media = httpMediaUrl("https://cdn.example/s.mp4")!!
        val ref = pageReferer(page, media)
        assertEquals("https://example.net:9443/", ref)
        val headers = DownloadAuth("test", ref, useBrowserCookies = true).headersFor(media, media)
        assertFalse(headers.containsKey("Origin"))
        assertFalse(DownloadAuth("test", "https://user:pw@example.net/").headersFor(media, media).containsKey("Referer"))
        val untrusted = ObservedMediaRequest.from(mapOf("User-Agent" to "text\r\nCookie: bogus", "Accept" to " "))
        assertNull(untrusted.userAgent)
        assertNull(untrusted.accept)
    }

    @Test fun knownSignedExpiryDoesNotRewriteTheLink() {
        val expired = httpMediaUrl("https://cdn.googlevideo.com/videoplayback?expire=1700000000&sig=a%2Bb")!!
        assertTrue(expired.isExpiredPlayerLink(nowEpochSeconds = 1700000001))
        assertFalse(expired.isExpiredPlayerLink(nowEpochSeconds = 1699999999))
        assertEquals("expire=1700000000&sig=a%2Bb", expired.encodedQuery)
        assertFalse(httpMediaUrl("https://other.example/media?expire=1700000000")!!.isExpiredPlayerLink(nowEpochSeconds = 1700000001))
    }

    @Test fun rangeValidationUnderstandsPartialContent() {
        assertEquals(ContentRange(101, 199, 200), ContentRange.parse("bytes 101-199/200"))
        assertEquals(99L, ContentRange.parse("bytes 101-199/200")!!.length)
        assertNull(ContentRange.parse("bytes 101-99/200"))
        assertNull(ContentRange.parse("bytes 101-199/199"))
        assertNull(ContentRange.parse("bytes */200"))
        assertTrue(ContentRange(101, 150, 200).matchesResume(101, 200))
        assertFalse(ContentRange(101, 150, 201).matchesResume(101, 200))
        assertFalse(ContentRange(102, 150, 200).matchesResume(101, 200))
        assertFalse(ContentRange(101, 150, null).matchesResume(101, 200))
    }

    @Test fun localHlsKeepsTimingEncryptionAndInitializersAndResolvesRanges() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:42
            #EXT-X-KEY:METHOD=AES-128,URI="keys/current?k=a%2Bb",IV=0x0000000000000000000000000000002a
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="160@0"
            #EXTINF:4.512,
            #EXT-X-BYTERANGE:1024@160
            main.mp4
            #EXT-X-DISCONTINUITY
            #EXTINF:2.1,
            #EXT-X-BYTERANGE:512
            main.mp4
            #EXT-X-PRELOAD-HINT:TYPE=PART,URI="online.m4s"
            #EXT-X-ENDLIST
        """.trimIndent()
        val plan = Hls.plan(HlsDocument(httpMediaUrl("https://cdn.example/rendition/video?id=21")!!, media), "video")
        assertEquals(listOf(null, "bytes=0-159", "bytes=160-1183", "bytes=1184-1695"), plan.resources.map { it.range?.header })
        assertEquals("https://cdn.example/rendition/keys/current?k=a%2Bb", plan.resources.first().url.toString())
        assertTrue(plan.resources.first().key)
        assertEquals(6.612, plan.duration, 0.00001)
        assertTrue(plan.text.contains("#EXT-X-MEDIA-SEQUENCE:42"))
        assertTrue(plan.text.contains("#EXT-X-DISCONTINUITY"))
        assertTrue(plan.text.contains("#EXTINF:4.512,"))
        assertTrue(plan.text.contains("IV=0x0000000000000000000000000000002a"))
        assertTrue(plan.text.contains("#EXT-X-MAP:URI=\"video-init-0.mp4\""))
        assertFalse(plan.text.contains("BYTERANGE"))
        assertFalse(plan.text.contains("online.m4s"))
        assertFalse(plan.text.contains("https://"))
    }

    @Test fun protectedRenditionAndSessionKeysAreBlocked() {
        val fairplay = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://license\",KEYFORMAT=\"com.apple.streamingkeydelivery\""
        assertEquals(DRMKind.FAIRPLAY, Hls.inspect(fairplay))
        assertThrows(ProtectedMedia::class.java) { Hls.plan(HlsDocument(httpMediaUrl("https://example.net/v")!!, fairplay), "video") }
        val widevine = "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,URI=\"https://example.net/l\",KEYFORMAT=\"urn:uuid:edef8ba9\""
        assertEquals(DRMKind.WIDEVINE, Hls.inspect(widevine))
        assertEquals(DRMKind.ENCRYPTED_HLS, Hls.inspect("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"custom\",URI=\"/key\""))
        assertEquals(DRMKind.NONE, Hls.inspect("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/hls\""))
        assertEquals(DRMKind.NONE, Hls.inspect("#EXTM3U\n#EXT-X-KEY:METHOD=NONE"))
    }

    @Test fun selectedVideoRenditionRetainsItsDefaultSeparateAudio() {
        val master = HlsDocument(httpMediaUrl("https://cdn.example/s/master?id=18")!!, """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="lang",NAME="English",DEFAULT=NO,URI="en/audio"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="lang",NAME="Arabic",DEFAULT=YES,URI="ar/audio"
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=600000,BANDWIDTH=900000,RESOLUTION=640x360,AUDIO="lang",CODECS="avc1.64001e,mp4a.40.2"
            # comment
            low?sig=low
            #EXT-X-STREAM-INF:BANDWIDTH=2200000,RESOLUTION=1280x720,AUDIO="lang",CODECS="avc1.64001e,mp4a.40.2"
            high?sig=high
        """.trimIndent())
        val variants = Hls.parseMaster(master.text, master.url.toString())
        val selected = Hls.pickVariant(variants, 480)!!
        assertEquals(900000, selected.bandwidth)
        assertEquals("https://cdn.example/s/low?sig=low", selected.url)
        val audio = Hls.selectAudio(master, selected)!!
        assertEquals("https://cdn.example/s/ar/audio", audio.url.toString())
        val local = Hls.localMaster(selected, audio)
        assertTrue(local.contains("URI=\"audio.m3u8\""))
        assertTrue(local.contains("CODECS=\"avc1.64001e,mp4a.40.2\""))
        assertTrue(local.endsWith("video.m3u8\n"))
    }
}
