package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.MediaHttp
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.requireMediaSuccess
import com.ahcrazy.video2.core.media.retryMediaRead
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import com.ahcrazy.video2.data.MediaProbe
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MediaProbeHttpTest {
    private val sample = byteArrayOf(0, 0, 0, 24) + "ftypisom00000000".toByteArray()

    @Test fun signedUrlUsesSessionAndFallsBackWhenRangeIsDenied() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(mediaReply())
            val remote = server.url("/media?sig=a%2Bb&token=%2F").toString()
            val referer = server.url("/watch?id=12").toString()
            val auth = DownloadAuth("Video2 test UA", referer, useBrowserCookies = true)
            val result = MediaProbe(MediaHttp { "session=owned-media" }).check(DetectedMedia(remote, "sample", MediaKind.OTHER), auth)
            assertTrue(result.probed)
            assertNull(result.probeProblem)
            assertEquals(MediaKind.MP4, result.kind)
            assertEquals(sample.size.toLong(), result.byteSize)
            assertEquals(remote, result.url)
            for (range in listOf("bytes=0-4095", null)) {
                val seen = server.nextRequest()
                assertEquals("GET", seen.method)
                assertEquals("/media?sig=a%2Bb&token=%2F", seen.path)
                assertEquals(range, seen.getHeader("Range"))
                assertEquals("session=owned-media", seen.getHeader("Cookie"))
                assertEquals(referer, seen.getHeader("Referer"))
                assertEquals("Video2 test UA", seen.getHeader("User-Agent"))
            }
        }
    }

    @Test fun accessExpiryAndHtmlAreDifferentFromPlayableMedia() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatchWith { r ->
                when (r.path) {
                    "/denied.mp4" -> MockResponse().setResponseCode(403)
                    "/expired.mp4" -> MockResponse().setResponseCode(410)
                    "/login.mp4" -> MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("<html>Sign in</html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val probe = MediaProbe(MediaHttp { null })
            for ((path, reason) in listOf("denied" to MediaProblem.ACCESS, "expired" to MediaProblem.EXPIRED, "login" to MediaProblem.NOT_MEDIA)) {
                val checked = probe.check(DetectedMedia(server.url("/$path.mp4").toString(), "sample", MediaKind.MP4), DownloadAuth.default)
                assertEquals(reason, checked.probeProblem)
                assertFalse(checked.canDownload)
                assertFalse(checked.canPlay)
            }
        }
    }

    @Test fun checksVariantAndItsAudioButNeverFetchesProtectedKeys() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatchWith { r ->
                val text = when (r.path) {
                    "/master" -> "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Default\",DEFAULT=YES,URI=\"audio\"\n" +
                        "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO=\"a\"\nvideo\n"
                    "/video" -> "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n"
                    "/audio" -> "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.apple.streamingkeydelivery\",URI=\"/license\"\n"
                    else -> "unexpected request"
                }
                MockResponse().setHeader("Content-Type", "text/plain").setBody(text)
            }
            val checked = MediaProbe(MediaHttp { null }).check(DetectedMedia(server.url("/master").toString(), "sample", MediaKind.HLS), DownloadAuth.default)
            assertEquals(DRMKind.FAIRPLAY, checked.drm)
            assertFalse(checked.canDownload)
            assertEquals(3, server.requestCount)
            assertEquals(listOf("/master", "/video", "/audio"), List(3) { server.nextRequest().path })
        }
    }

    @Test fun onlyTransientReadsAreRetried() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(mediaReply())
            server.enqueue(MockResponse().setResponseCode(403))
            val http = MediaHttp { null }
            suspend fun get(path: String) = retryMediaRead {
                http.read(http.request(httpMediaUrl(server.url("/$path").toString())!!, DownloadAuth.default).build()) { response ->
                    response.requireMediaSuccess()
                    response.body!!.bytes()
                }
            }
            assertArrayEquals(sample, get("temporary"))
            assertEquals(2, server.requestCount)
            assertTrue(runCatching { get("blocked") }.isFailure)
            assertEquals(3, server.requestCount)
        }
    }

    private fun mediaReply() = MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(sample))
    private fun MockWebServer.nextRequest() = checkNotNull(takeRequest(2, TimeUnit.SECONDS))
    private fun MockWebServer.dispatchWith(f: (RecordedRequest) -> MockResponse) {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = f(request) }
    }
}
