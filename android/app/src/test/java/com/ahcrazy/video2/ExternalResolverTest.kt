package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.MediaDownloadException
import com.ahcrazy.video2.core.media.PublicMediaReads
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.externalTransport
import com.ahcrazy.video2.core.media.externalHttpsUrl
import com.ahcrazy.video2.core.media.isPublicMediaAddress
import com.ahcrazy.video2.core.media.resolverEndpoint
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.data.ExternalResolver
import com.ahcrazy.video2.data.ResolverFailure
import com.ahcrazy.video2.data.ResolverProblem
import com.ahcrazy.video2.data.ResolverType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ExternalResolverTest {
    private val id = "AbCdEfG_123"
    private val canonical = "https://www.youtube.com/watch?v=$id"
    private val server = resolverEndpoint("https://media.example.net/api/")!!
    private val video = YouTubeVideo.parse(canonical)!!

    @Test fun onlyActualWatchLinksAndTheEmbeddedVideoIdentityAreAccepted() {
        listOf("$canonical&si=share", "https://youtu.be/$id?t=10", "https://www.youtube.com/shorts/$id?si=id", "https://www.youtube-nocookie.com/embed/$id")
            .forEach { assertEquals(canonical, YouTubeVideo.parse(it)?.watchUrl) }
        listOf("https://www.google.com/search?q=$id", "https://r1.googlevideo.com/videoplayback?id=$id&sig=secret",
            "https://www.youtube.com.example/watch?v=$id", "https://www.youtube.com/redirect?v=$id",
            "https://www.youtube.com/watch?v=bad", "https://username@www.youtube.com/watch?v=$id")
            .forEach { assertNull(it, YouTubeVideo.parse(it)) }
        val embedded = DetectedMedia("https://r1.googlevideo.com/videoplayback?sig=secret", "Video", MediaKind.MP4,
            pageUrl = "https://www.google.com/search?q=not-a-video", requestReferer = "https://www.youtube.com/embed/$id?session=private")
        assertEquals(canonical, YouTubeVideo.fromMedia(embedded)?.watchUrl)
        val search = requireNotNull(embedded.pageUrl)
        val other = embedded.copy(url = "https://r2.googlevideo.com/videoplayback?sig=other", requestReferer = "https://www.youtube.com/embed/ZyXwVu65432")
        assertEquals(video, YouTubeVideo.forCapture(search, listOf(embedded, other.copy(fromAd = true))))
        assertNull(YouTubeVideo.forCapture(search, listOf(embedded, other)))
        assertEquals(video, YouTubeVideo.forCapture(search, listOf(embedded, other), selected = embedded))
        assertNull(YouTubeVideo.forCapture(search, emptyList()))
    }

    @Test fun cobaltSendsJustTheCanonicalPageAndAcceptsPreparedFileFromTheSelectedServer() = runBlocking<Unit> {
        var seen: Request? = null
        val client = fakeHttp { request ->
            seen = request
            200 to """{"status":"tunnel","url":"https://media.example.net/tunnel?id=opaque&sig=a%2Bb","filename":"example.mp4"}"""
        }
        val found = ExternalResolver(client).resolve(ResolverType.COBALT, server, video, 720, null, "test-key").single()
        val request = requireNotNull(seen)
        assertEquals("POST", request.method)
        assertEquals(server, request.url)
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val json = JSONObject(body)
        assertEquals(canonical, json.getString("url"))
        assertEquals("720", json.getString("videoQuality"))
        assertTrue(json.getBoolean("alwaysProxy"))
        assertEquals("Api-Key test-key", request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertNull(request.header("Referer"))
        assertFalse(body.contains("test-key"))
        assertEquals("https://media.example.net/tunnel?id=opaque&sig=a%2Bb", found.url)
        assertEquals("external-cobalt", found.extractionMethod)
        assertEquals(MediaKind.MP4, found.kind)
        assertEquals(canonical, found.pageUrl)
        assertFalse(DownloadAuth.external.useBrowserCookies)
        assertTrue(DownloadAuth.external.externalHttpsOnly)
    }

    @Test fun pipedKeepsOnlyMuxedFormatsAndHlsAndDoesNotSendAnApiKey() = runBlocking<Unit> {
        val resolver = ExternalResolver(fakeHttp { request ->
            assertEquals("/api/streams/$id", request.url.encodedPath)
            assertNull(request.header("Authorization"))
            200 to """{
              "title":"Example", "duration":71, "hls":"https://piped.example.net/manifest?token=a",
              "videoStreams":[
                {"url":"https://piped.example.net/video/high?sig=a%2Bb","videoOnly":false,"height":1080,"mimeType":"video/mp4"},
                {"url":"https://piped.example.net/video/low?sig=a%2Bb","videoOnly":false,"height":360,"mimeType":"video/mp4"},
                {"url":"https://piped.example.net/no-audio.mp4","videoOnly":true,"mimeType":"video/mp4"},
                {"url":"https://piped.example.net/uncertain.mp4","mimeType":"video/mp4"},
                {"url":"https://piped.example.net/segment.m4s","videoOnly":false,"mimeType":"video/mp4"}
              ]}"""
        })
        val found = resolver.resolve(ResolverType.PIPED, server, video, 720, null, "not-for-this-server")
        assertEquals(listOf(MediaKind.MP4, MediaKind.HLS, MediaKind.MP4), found.map { it.kind })
        assertEquals(listOf(360, null, 1080), found.map { it.height })
        assertTrue(found.all { it.durationSec == 71.0 })
    }

    @Test fun invidiousIgnoresAdaptiveAndPrivateTargetsAndKeepsPlayableContainers() = runBlocking<Unit> {
        val resolver = ExternalResolver(fakeHttp { request ->
            assertEquals("/api/api/v1/videos/$id", request.url.encodedPath)
            assertEquals("true", request.url.queryParameter("local"))
            200 to """{"videoId":"$id", "adaptiveFormats":[{"url":"https://host.example.net/video-only.mp4"}],
               "formatStreams":[{"url":"/videoplayback?sig=a%2Bb","container":"webm","qualityLabel":"480p"},
               {"url":"https://localhost/watch.mp4","container":"mp4"},
               {"url":"http://host.example.net/insecure.mp4","container":"mp4"}]}"""
        })
        val found = resolver.resolve(ResolverType.INVIDIOUS, server, video, 0, null, null).single()
        assertEquals(MediaKind.WEBM, found.kind)
        assertEquals(480, found.height)
        assertEquals("https://media.example.net/videoplayback?sig=a%2Bb", found.url)
    }

    @Test fun invidiousAsksItsOwnHlsApiToProxyAStreamWithoutRewritingAnUpstreamUrl() {
        val resolver = ExternalResolver()
        fun returned(raw: String) = resolver.parse(ResolverType.INVIDIOUS, server, video,
            JSONObject().put("videoId", id).put("formatStreams", org.json.JSONArray()).put("hlsUrl", raw), null, 720).single()
        val proxy = returned("https://media.example.net/api/manifest/hls_variant/episode/index.m3u8?sig=a%2Bb")
        assertEquals(MediaKind.HLS, proxy.kind)
        assertEquals("https://media.example.net/api/manifest/hls_variant/episode/index.m3u8?sig=a%2Bb&local=true", proxy.url)
        val upstream = "https://www.youtube.com/api/manifest/hls_playlist/index.m3u8?sig=orig"
        assertEquals(upstream, returned(upstream).url)
    }

    @Test fun accessDenialsDoNotFanOutOrParseErrorPagesAsVideo() = runBlocking<Unit> {
        var requests = 0
        val resolver = ExternalResolver(fakeHttp { requests++; 403 to "<html>denied</html>" })
        val error = runCatching { resolver.resolve(ResolverType.COBALT, server, video, 720, null, "test-key") }.exceptionOrNull()
        assertEquals(ResolverProblem.ACCESS, (error as ResolverFailure).problem)
        assertEquals(1, requests)
    }

    @Test fun externalTransportRejectsPrivateAddressesAndUnexpectedTunnelHosts() {
        assertNull(externalHttpsUrl("https://127.0.0.1/private"))
        assertNull(externalHttpsUrl("https://internal.local/private"))
        assertNull(resolverEndpoint("https://media.example.net/?key=private"))
        listOf("10.0.0.1", "169.254.169.254", "192.168.0.1", "100.64.1.1", "::1", "fc00::1", "2002:7f00:1::", "2001:0::1").forEach {
            assertFalse(it, isPublicMediaAddress(InetAddress.getByName(it)))
        }
        assertThrows(ResolverFailure::class.java) {
            ExternalResolver().parse(ResolverType.COBALT, server, video,
                JSONObject("""{"status":"tunnel","url":"https://unselected.example.net/tunnel","filename":"sample.mp4"}"""), null, 720)
        }
    }

    @Test fun noExternalResultCanCarryBrowserCredentialsToMediaOrRedirects() {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().externalTransport().addInterceptor(PublicMediaReads).addInterceptor { chain ->
            val request = chain.request()
            requests += request
            if (requests.size == 1) fixture(request, 302).header("Location", "https://cdn.example.net/a?sig=a%2Bb").build()
            else fixture(request, 200).build()
        }.build()
        val request = Request.Builder().url("https://media.example.net/file")
            .header("Authorization", "Api-Key secret").header("Cookie", "session=private")
            .header("Proxy-Authorization", "private").header("Referer", "https://example.net/secret")
            .header("Origin", "https://example.net").header("Range", "bytes=12-").build()
        http.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(2, requests.size)
        assertEquals("https://cdn.example.net/a?sig=a%2Bb", requests.last().url.toString())
        requests.forEach { seen ->
            listOf("Authorization", "Cookie", "Proxy-Authorization", "Referer", "Origin").forEach { assertNull(it, seen.header(it)) }
            assertEquals("bytes=12-", seen.header("Range"))
        }
    }

    @Test fun publicHttpsChecksApplyBeforeFollowingAnyMediaRedirect() {
        for (location in listOf("http://media.example.net/insecure", "https://127.0.0.1/private", "https://auth@media.example.net/file", "https://tools.internal/")) {
            var requests = 0
            val http = OkHttpClient.Builder().externalTransport().addInterceptor(PublicMediaReads).addInterceptor { chain ->
                requests++
                fixture(chain.request(), 302).header("Location", location).build()
            }.build()
            val request = Request.Builder().url("https://media.example.net/file").build()
            assertThrows(location, MediaDownloadException::class.java) { http.newCall(request).execute().close() }
            assertEquals(location, 1, requests)
        }
    }

    private fun fixture(request: Request, code: Int) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message("fixture").body("".toResponseBody())

    private fun fakeHttp(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val (code, text) = reply(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(text.toResponseBody("application/json".toMediaType())).build()
    }.build()
}
