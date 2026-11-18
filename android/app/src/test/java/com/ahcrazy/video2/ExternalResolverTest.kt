package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.YouTubeVideo
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
@Config(sdk = [34])
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
            200 to """{"videoId":"$id", "adaptiveFormats":[{"url":"https://host.example.net/video-only.mp4"}],
               "formatStreams":[{"url":"https://host.example.net/full","container":"webm","qualityLabel":"480p"},
               {"url":"https://localhost/watch.mp4","container":"mp4"},
               {"url":"http://host.example.net/insecure.mp4","container":"mp4"}]}"""
        })
        val found = resolver.resolve(ResolverType.INVIDIOUS, server, video, 0, null, null).single()
        assertEquals(MediaKind.WEBM, found.kind)
        assertEquals(480, found.height)
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
        listOf("10.0.0.1", "169.254.169.254", "192.168.0.1", "100.64.1.1", "::1", "fc00::1").forEach {
            assertFalse(it, isPublicMediaAddress(InetAddress.getByName(it)))
        }
        assertThrows(ResolverFailure::class.java) {
            ExternalResolver().parse(ResolverType.COBALT, server, video,
                JSONObject("""{"status":"tunnel","url":"https://unselected.example.net/tunnel","filename":"sample.mp4"}"""), null, 720)
        }
    }

    private fun fakeHttp(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val (code, text) = reply(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(text.toResponseBody("application/json".toMediaType())).build()
    }.build()
}
