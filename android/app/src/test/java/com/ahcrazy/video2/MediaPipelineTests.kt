package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.ContentRange
import com.ahcrazy.video2.core.media.MediaDownloadException
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.data.Hls
import com.ahcrazy.video2.data.HlsDocument
import com.ahcrazy.video2.data.ProtectedMedia
import com.ahcrazy.video2.core.media.responseMediaKind
import org.junit.Assert.*
import org.junit.Test

class MediaPipelineTests {
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

    @Test fun originsKeepPortAndNeverIncludeCredentialsOrCookies() {
        val headers = DownloadAuth("test", "https://example.net:9443/embed?id=2#fragment", useBrowserCookies = true).headers()
        assertEquals("https://example.net:9443/embed?id=2", headers["Referer"])
        assertEquals("https://example.net:9443", headers["Origin"])
        assertFalse(headers.containsKey("Cookie"))
        assertFalse(DownloadAuth("test", "https://user:pw@example.net/").headers().containsKey("Referer"))
    }

    @Test fun rangeValidationUnderstandsPartialContent() {
        assertEquals(ContentRange(101, 199, 200), ContentRange.parse("bytes 101-199/200"))
        assertEquals(99L, ContentRange.parse("bytes 101-199/200")!!.length)
        assertNull(ContentRange.parse("bytes 101-99/200"))
        assertNull(ContentRange.parse("bytes 101-199/199"))
        assertNull(ContentRange.parse("bytes */200"))
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
