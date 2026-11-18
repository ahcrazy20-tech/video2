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
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MediaProbeHttpTest {
    private val sample = byteArrayOf(0, 0, 0, 24) + "ftypisom00000000".toByteArray()

    @Test fun signedUrlUsesSessionAndFallsBackWhenRangeIsDenied() = runBlocking<Unit> {
        val seen = CopyOnWriteArrayList<List<String?>>()
        server({ app ->
            app.createContext("/media") { e ->
                seen.add(listOf(e.requestMethod, e.requestURI.rawQuery, e.requestHeaders.getFirst("Cookie"),
                    e.requestHeaders.getFirst("Referer"), e.requestHeaders.getFirst("User-Agent")))
                if (e.requestHeaders.getFirst("Range") != null) e.reply(416)
                else e.reply(200, "application/octet-stream", sample)
            }
        }) { base ->
            val remote = "$base/media?sig=a%2Bb&token=%2F"
            val auth = DownloadAuth("Video2 test UA", "$base/watch?id=12", useBrowserCookies = true)
            val result = MediaProbe(MediaHttp { "session=owned-media" }).check(DetectedMedia(remote, "sample", MediaKind.OTHER), auth)
            assertTrue(result.probed)
            assertNull(result.probeProblem)
            assertEquals(MediaKind.MP4, result.kind)
            assertEquals(sample.size.toLong(), result.byteSize)
            assertEquals(remote, result.url)
            assertEquals(List(2) { listOf("GET", "sig=a%2Bb&token=%2F", "session=owned-media", "$base/watch?id=12", "Video2 test UA") }, seen)
        }
    }

    @Test fun accessAndExpiredAndHtmlAreDifferentFromPlayableMedia() = runBlocking<Unit> {
        server({ app ->
            app.createContext("/denied.mp4") { it.reply(403) }
            app.createContext("/expired.mp4") { it.reply(410) }
            app.createContext("/login.mp4") { it.reply(200, "application/octet-stream", "<html>Sign in</html>".toByteArray()) }
        }) { base ->
            val probe = MediaProbe(MediaHttp { null })
            for ((path, reason) in listOf("denied" to MediaProblem.ACCESS, "expired" to MediaProblem.EXPIRED, "login" to MediaProblem.NOT_MEDIA)) {
                val checked = probe.check(DetectedMedia("$base/$path.mp4", "sample", MediaKind.MP4), DownloadAuth.default)
                assertEquals(reason, checked.probeProblem)
                assertFalse(checked.canDownload)
                assertFalse(checked.canPlay)
            }
        }
    }

    @Test fun checksVariantAndItsAudioButNeverFetchesProtectedKeys() = runBlocking<Unit> {
        val seen = CopyOnWriteArrayList<String>()
        server({ app ->
            app.createContext("/") { e ->
                val path = e.requestURI.path
                seen.add(path)
                val text = when (path) {
                    "/master" -> "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Default\",DEFAULT=YES,URI=\"audio\"\n" +
                        "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO=\"a\"\nvideo\n"
                    "/video" -> "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n"
                    "/audio" -> "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.apple.streamingkeydelivery\",URI=\"/license\"\n"
                    else -> "unexpected request"
                }
                e.reply(200, "text/plain", text.toByteArray())
            }
        }) { base ->
            val checked = MediaProbe(MediaHttp { null }).check(DetectedMedia("$base/master", "sample", MediaKind.HLS), DownloadAuth.default)
            assertEquals(DRMKind.FAIRPLAY, checked.drm)
            assertFalse(checked.canDownload)
            assertEquals(listOf("/master", "/video", "/audio"), seen)
        }
    }

    @Test fun onlyTransientReadsAreRetried() = runBlocking<Unit> {
        val temporary = AtomicInteger()
        val blocked = AtomicInteger()
        server({ app ->
            app.createContext("/temporary") { e -> if (temporary.incrementAndGet() == 1) e.reply(503) else e.reply(200, "video/mp4", sample) }
            app.createContext("/blocked") { e -> blocked.incrementAndGet(); e.reply(403) }
        }) { base ->
            val http = MediaHttp { null }
            suspend fun get(path: String) = retryMediaRead {
                http.read(http.request(httpMediaUrl("$base/$path")!!, DownloadAuth.default).build()) { response ->
                    response.requireMediaSuccess()
                    response.body!!.bytes()
                }
            }
            assertArrayEquals(sample, get("temporary"))
            assertEquals(2, temporary.get())
            assertTrue(runCatching { get("blocked") }.isFailure)
            assertEquals(1, blocked.get())
        }
    }

    private suspend fun server(configure: (HttpServer) -> Unit, use: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            configure(server)
            server.start()
            use("http://127.0.0.1:${server.address.port}")
        } finally { server.stop(0) }
    }

    private fun HttpExchange.reply(code: Int, mime: String? = null, bytes: ByteArray = byteArrayOf()) {
        use { e ->
            if (mime != null) e.responseHeaders.set("Content-Type", mime)
            e.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) e.responseBody.write(bytes)
        }
    }
}
