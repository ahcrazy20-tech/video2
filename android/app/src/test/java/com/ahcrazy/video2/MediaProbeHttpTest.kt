package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.DownloadCooldowns
import com.ahcrazy.video2.core.media.MediaHttp
import com.ahcrazy.video2.core.media.mp4TrackTypes
import com.ahcrazy.video2.core.media.ObservedMediaRequest
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.requireMediaSuccess
import com.ahcrazy.video2.core.media.retryMediaRead
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.JoinedTracks
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.HlsVariant
import com.ahcrazy.video2.core.model.MediaProblem
import com.ahcrazy.video2.data.MediaProbe
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test fun signedUrlUsesObservedHeadersAndDoesNotInventOrigin() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val remote = server.url("/media?sig=a%2Bb&token=%2F")
            val referer = server.url("/watch?id=12").toString()
            server.dispatchWith { r ->
                if (r.getHeader("Origin") != null || r.getHeader("Referer") != referer || r.getHeader("Cookie") != "session=owned-media")
                    MockResponse().setResponseCode(403) else mediaReply()
            }
            val auth = ObservedMediaRequest.from(mapOf("Referer" to referer, "User-Agent" to "Video2 test UA", "Accept" to "video/mp4"))
                .authFor(remote, "fallback")
            val result = MediaProbe(MediaHttp { "session=owned-media" }).check(DetectedMedia(remote.toString(), "sample", MediaKind.OTHER), auth)
            assertTrue(result.probed)
            assertTrue(result.downloadChecked())
            assertNull(result.probeProblem)
            assertEquals(MediaKind.MP4, result.kind)
            assertEquals(sample.size.toLong(), result.byteSize)
            assertEquals(remote.toString(), result.url)
            val seen = server.nextRequest()
            assertEquals("GET", seen.method)
            assertEquals("/media?sig=a%2Bb&token=%2F", seen.path)
            assertNull(seen.getHeader("Range"))
            assertEquals("Video2 test UA", seen.getHeader("User-Agent"))
            assertEquals("video/mp4", seen.getHeader("Accept"))
        }
    }

    @Test fun previewAccessCannotMarkAForbiddenFullGetAsDownloadable() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatchWith { r -> if (r.getHeader("Range") != null) mediaReply() else MockResponse().setResponseCode(403) }
            val checked = MediaProbe(MediaHttp { null }).check(DetectedMedia(server.url("/video.mp4").toString(), "sample", MediaKind.MP4), DownloadAuth.default)
            assertEquals(MediaProblem.ACCESS, checked.probeProblem)
            assertEquals(403, checked.probeHttpStatus)
            assertFalse(checked.downloadChecked())
            assertFalse(checked.canDownload)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectUsesItsOwnCookiesAndDoesNotForwardCapturedOriginOrAccept() = runBlocking<Unit> {
        MockWebServer().use { source -> MockWebServer().use { destination ->
            val url = source.url("/redirect")
            val ref = "https://watch.example/player?opaque=private"
            val auth = ObservedMediaRequest.from(mapOf("Referer" to ref, "Origin" to "https://watch.example", "Accept" to "video/mp4"))
                .authFor(url, "WebView test")
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/video").toString()))
            destination.enqueue(mediaReply())
            // Use HTTP fixture origins, with an HTTP referrer, to exercise a redirect rather than the HTTPS downgrade guard.
            val requested = auth.copy(referer = "http://watch.example/player?opaque=private", origin = "http://watch.example")
            val probe = MediaProbe(MediaHttp { if (it == url) "session=first" else "session=destination" })
            val result = probe.check(DetectedMedia(url.toString(), "sample", MediaKind.OTHER), requested)
            assertNull(result.probeProblem)
            val first = source.nextRequest()
            assertEquals("session=first", first.getHeader("Cookie"))
            assertEquals("http://watch.example", first.getHeader("Origin"))
            assertEquals("video/mp4", first.getHeader("Accept"))
            val redirected = destination.nextRequest()
            assertEquals("session=destination", redirected.getHeader("Cookie"))
            assertEquals("http://watch.example/", redirected.getHeader("Referer"))
            assertNull(redirected.getHeader("Origin"))
            assertNull(redirected.getHeader("Accept"))
        } }
    }

    @Test fun accessExpiryAndHtmlAreDifferentFromPlayableMedia() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatchWith { r ->
                when (r.path) {
                    "/denied.mp4" -> MockResponse().setResponseCode(403)
                    "/sign-in.mp4" -> MockResponse().setResponseCode(401)
                    "/expired.mp4" -> MockResponse().setResponseCode(410)
                    "/login.mp4" -> MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("<html>Sign in</html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val probe = MediaProbe(MediaHttp { null })
            for ((path, reason) in listOf("denied" to MediaProblem.ACCESS, "sign-in" to MediaProblem.SIGN_IN, "expired" to MediaProblem.EXPIRED, "login" to MediaProblem.NOT_MEDIA)) {
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

    @Test fun automaticHlsCheckUsesOnlyAnAvailableRenditionFromTheSamePlaylist() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080\nhigh?sig=test\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=640x360\nlow?sig=test\n"
            val variant = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n"
            server.dispatchWith { r -> when (r.path) {
                "/master" -> MockResponse().setBody(master)
                "/high?sig=test" -> MockResponse().setResponseCode(403)
                "/low?sig=test" -> MockResponse().setBody(variant)
                else -> MockResponse().setResponseCode(404)
            } }
            val checked = MediaProbe(MediaHttp { null }).check(DetectedMedia(server.url("/master").toString(), "sample", MediaKind.HLS), DownloadAuth.default)
            assertNull(checked.probeProblem)
            assertEquals(360, checked.height)
            assertEquals(listOf("/master", "/high?sig=test", "/low?sig=test"), List(3) { server.nextRequest().path })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun exactHlsQualityDoesNotTryAnotherRenditionIfTheSelectedOneIsDenied() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080\nhigh\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=640x360\nlow\n"
            server.dispatchWith { r -> when (r.path) {
                "/master" -> MockResponse().setBody(master)
                "/high" -> MockResponse().setResponseCode(403)
                else -> MockResponse().setResponseCode(404)
            } }
            val media = DetectedMedia(server.url("/master").toString(), "sample", MediaKind.HLS)
            val exact = HlsVariant(server.url("/high").toString(), height = 1080)
            val checked = MediaProbe(MediaHttp { null }).check(media, DownloadAuth.default, automatic = true, exactVariant = exact)
            assertEquals(MediaProblem.ACCESS, checked.probeProblem)
            assertFalse(checked.downloadChecked())
            assertEquals(listOf("/master", "/high"), List(2) { server.nextRequest().path })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun externalHlsChecksTheSegmentsNotJustTheManifestAndKeepsTheVerifiedRendition() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        val fixture = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            assertNull(request.header("Cookie"))
            assertNull(request.header("Referer"))
            requests += path
            val text = when (path) {
                "/manifest" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080\nhigh\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=640x360\nlow\n"
                "/high", "/low" -> "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n$path.ts\n#EXT-X-ENDLIST\n"
                "/low.ts" -> "sample segment"
                else -> "denied"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(if (path == "/high.ts") 403 else 200).body(text.toResponseBody()).build()
        }
        val http = MediaHttp(fixture) { error("External media must not consult the browser’s cookies") }
        val media = DetectedMedia("https://media.example.net/manifest", "sample", MediaKind.HLS, extractionMethod = "external-invidious")
        val result = MediaProbe(http).check(media, DownloadAuth.external)
        assertTrue(result.downloadChecked())
        assertEquals(360, result.height)
        assertEquals("https://media.example.net/low", result.checkedVariantUrl)
        assertEquals(listOf("/manifest", "/high", "/high.ts", "/low", "/low.ts"), requests)
    }

    @Test fun smartHlsRequiresAnEndedWindowAndARealAudioResponse() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Default\",DEFAULT=YES,URI=\"audio\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO=\"a\"\nvideo\n"
            val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n"
            val ended = java.util.concurrent.atomic.AtomicBoolean()
            val audioAvailable = java.util.concurrent.atomic.AtomicBoolean()
            server.dispatchWith { r -> when (r.path) {
                "/master" -> MockResponse().setBody(master)
                "/video" -> MockResponse().setBody(vod + "video.ts\n" + if (ended.get()) "#EXT-X-ENDLIST\n" else "")
                "/audio" -> MockResponse().setBody(vod + "audio.ts\n#EXT-X-ENDLIST\n")
                "/video.ts" -> MockResponse().setBody("video sample")
                "/audio.ts" -> if (audioAvailable.get()) MockResponse().setBody("audio sample") else MockResponse().setResponseCode(403)
                else -> MockResponse().setResponseCode(404)
            } }
            val probe = MediaProbe(MediaHttp { null })
            val media = DetectedMedia(server.url("/master").toString(), "sample", MediaKind.HLS, durationSec = 6.0)
            assertEquals(MediaProblem.NOT_MEDIA, probe.check(media, DownloadAuth.default, automatic = true).probeProblem)
            assertEquals(3, server.requestCount) // A live window is rejected before downloading any segment.
            ended.set(true)
            assertEquals(403, probe.check(media, DownloadAuth.default, automatic = true).probeHttpStatus)
            audioAvailable.set(true)
            val ready = probe.check(media, DownloadAuth.default, automatic = true)
            assertTrue(ready.downloadChecked())
            assertEquals(server.url("/video").toString(), ready.checkedVariantUrl)
            assertEquals(13, server.requestCount)
        }
    }

    @Test fun audioGroupFallbackKeepsTheSameVideoAndPinsTheWorkingGroup() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"high\",NAME=\"Default\",URI=\"high\"\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"low\",NAME=\"Default\",URI=\"low\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO=\"high\"\nvideo\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=980000,RESOLUTION=1280x720,AUDIO=\"low\"\nvideo\n"
            val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n"
            server.dispatchWith { r -> when (r.path) {
                "/master" -> MockResponse().setBody(master)
                "/video", "/high", "/low" -> MockResponse().setBody(playlist + "${r.path}.ts\n#EXT-X-ENDLIST\n")
                "/video.ts", "/low.ts" -> MockResponse().setBody("media bytes")
                else -> MockResponse().setResponseCode(403)
            } }
            val probe = MediaProbe(MediaHttp { null })
            val media = DetectedMedia(server.url("/master").toString(), "sample", MediaKind.HLS)
            val result = probe.check(media, DownloadAuth.default, automatic = true)
            assertTrue(result.downloadChecked())
            assertEquals("low", result.checkedAudioGroup)
            assertEquals(1, result.variants.size)
            assertEquals("low", result.variants.single().audioGroup)
            assertEquals(server.url("/video").toString(), result.checkedVariantUrl)
            assertEquals(listOf("/master", "/video", "/high", "/video.ts", "/high.ts", "/low", "/low.ts"),
                List(7) { server.nextRequest().path })
            assertEquals(7, server.requestCount)
        }
    }

    @Test fun verifiesBothActualMp4TrackHeadersAndDoesNotAcceptVideoAsAudio() = runBlocking<Unit> {
        val v = fixtureBytes("fixture-picture.mp4")
        val a = fixtureBytes("fixture-sound.m4a")
        assertEquals(setOf("vide"), v.mp4TrackTypes())
        assertEquals(setOf("soun"), a.mp4TrackTypes())
        assertTrue(a.copyOf(26).mp4TrackTypes().isEmpty())
        val requests = mutableListOf<String>()
        var substitutePictureForAudio = false
        val fixture = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val isVideo = request.url.encodedPath == "/picture.mp4"
            requests += request.url.encodedPath
            assertNull(request.header("Cookie"))
            assertNull(request.header("Referer"))
            assertNull(request.header("Range"))
            val data = if (isVideo || substitutePictureForAudio) v else a
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture").code(200)
                .body(data.toResponseBody((if (isVideo) "video/mp4" else "audio/mp4").toMediaType())).build()
        }
        val probe = MediaProbe(MediaHttp(fixture) { error("no browser session") })
        val media = DetectedMedia("https://media.example.net/picture.mp4", "sample", MediaKind.MP4,
            joinedTracks = JoinedTracks("https://media.example.net/sound.m4a", v.size.toLong(), a.size.toLong()))
        val valid = probe.check(media, DownloadAuth.external, automatic = true)
        assertTrue(valid.downloadChecked())
        assertEquals((v.size + a.size).toLong(), valid.byteSize ?: -1L)
        substitutePictureForAudio = true
        val wrong = probe.check(media.copy(joinedTracks = media.joinedTracks!!.copy(audioBytes = v.size.toLong())), DownloadAuth.external)
        assertFalse(wrong.downloadChecked())
        assertEquals(MediaProblem.NOT_MEDIA, wrong.probeProblem)
        assertEquals(listOf("/picture.mp4", "/sound.m4a", "/picture.mp4", "/sound.m4a"), requests)
    }

    @Test fun mediaCooldownKeepsTheSourceHostAndDoesNotRetryUntilDeadline() = runBlocking<Unit> {
        MockWebServer().use { server ->
            var now = System.currentTimeMillis()
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "175"))
            server.enqueue(mediaReply())
            val probe = MediaProbe(MediaHttp(cooldowns = DownloadCooldowns(now = { now })) { null })
            val media = DetectedMedia(server.url("/file.mp4").toString(), "sample", MediaKind.MP4)
            val limited = probe.check(media, DownloadAuth.default)
            assertEquals(429, limited.probeHttpStatus)
            assertEquals(server.url("/").host, limited.probeHost)
            assertEquals(now + 175_000L, limited.probeRetryAtMs ?: -1L)
            now += 60_000L
            val again = probe.check(media, DownloadAuth.default)
            assertEquals(limited.probeRetryAtMs, again.probeRetryAtMs)
            assertEquals(1, server.requestCount)
            now += 115_000L
            assertTrue(probe.check(media, DownloadAuth.default).downloadChecked())
            assertEquals(2, server.requestCount)
        }
    }

    private fun fixtureBytes(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/media/$name")).use { it.readBytes() }

    @Test fun onlyTransientReadsAreRetried() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(mediaReply())
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
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
            assertTrue(runCatching { get("rate-limited") }.isFailure)
            assertEquals(4, server.requestCount)
        }
    }

    private fun mediaReply() = MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(sample))
    private fun MockWebServer.nextRequest() = checkNotNull(takeRequest(2, TimeUnit.SECONDS))
    private fun MockWebServer.dispatchWith(f: (RecordedRequest) -> MockResponse) {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = f(request) }
    }
}
