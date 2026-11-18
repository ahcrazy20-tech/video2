package com.ahcrazy.video2

import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.data.PeerTubeDownloads
import com.ahcrazy.video2.data.PeerTubeVideo
import com.ahcrazy.video2.data.ResolverFailure
import com.ahcrazy.video2.data.ResolverProblem
import com.ahcrazy.video2.data.SmartDownloadRequest
import com.ahcrazy.video2.data.SmartDownloads
import com.ahcrazy.video2.data.SmartResult
import com.ahcrazy.video2.data.validPeerTubeMp4
import com.ahcrazy.video2.ui.browser.captureSmartPlan
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerTubeDownloadsTest {
    private val host = "https://tube.example.net"
    private val id = "8eb27d56-6341-40f9-85d1-82e0ebc8d717"
    private val page = "$host/videos/watch/$id"
    private val video = PeerTubeVideo.parse(page)!!

    @Test fun recognizedVideoIdIsReadOnlyFromThePublisherWithNoTrackingParameters() {
        assertEquals("$host/api/v1/videos/$id", PeerTubeVideo.parse("$page?token=private#thing")!!.api.toString())
        assertEquals(video, captureSmartPlan(page, emptyList())?.peerTube)
        for (url in listOf("$host/w/abcd", "$host/search?id=$id", "http://localhost/w/$id", "$host/w/%2f$id")) assertNull(PeerTubeVideo.parse(url))
    }

    @Test fun catalogRetainsOnlyPublishedSameOriginFilesWithVideoAndAudio() {
        val rows = PeerTubeDownloads.parse(video, metadata())
        assertEquals(listOf(720, 360), rows.map { it.height })
        assertEquals(listOf("$host/download/720.mp4", "$host/download/360.mp4"), rows.map { it.url })
        assertTrue(rows.all { it.kind == MediaKind.MP4 && it.checkedAtMs == null })
    }

    @Test fun unpublishedFederatedLiveOrDisabledVideoDoesNotOfferAStreamAsFallback() {
        for ((key, value) in listOf("downloadEnabled" to false, "isLocal" to false, "isLive" to true,
            "privacy" to JSONObject().put("id", 2), "state" to JSONObject().put("id", 2))) {
            val unavailable = runCatching { PeerTubeDownloads.parse(video, metadata().put(key, value)) }.exceptionOrNull() as ResolverFailure
            assertTrue(unavailable.problem in setOf(ResolverProblem.ACCESS, ResolverProblem.UNAVAILABLE))
        }
        assertEquals(ResolverProblem.UNSUPPORTED, (runCatching { PeerTubeDownloads.parse(video, metadata().put("uuid", "wrong")) }
            .exceptionOrNull() as ResolverFailure).problem)
    }

    @Test fun requestUsesPublisherAndVerifiesMediaWithoutBrowserCookiesOrYouTubeConsent() = runBlocking<Unit> {
        val files = PeerTubeDownloads.parse(video, metadata())
        val engine = SmartDownloads(resolve = { _, _, _, _ -> error("YouTube resolver must not be contacted") },
            resolvePeerTube = { assertEquals(video, it); files }, check = { c, h ->
                assertEquals(360, h)
                assertEquals(DownloadAuth.external.copy(rateLimitOwner = "tube.example.net"), c.auth)
                c.media.copy(checkedAtMs = System.currentTimeMillis())
            })
        val result = engine.find(SmartDownloadRequest(null, "Fixture", peerTube = video), 480,
            permitServers = { error("this isn't a third-party resolver") }, offerQualities = true) as SmartResult.Ready
        assertEquals(360, result.candidate.media.height)
        assertEquals(listOf(720, 360), result.qualities.map { it.height })
    }

    @Test fun mp4HeaderMustContainBothTracks() {
        val picture = javaClass.getResourceAsStream("/media/fixture-picture.mp4")!!.use { it.readBytes() }
        val audio = javaClass.getResourceAsStream("/media/fixture-sound.m4a")!!.use { it.readBytes() }
        assertFalse(validPeerTubeMp4(MediaKind.MP4, picture, picture.size.toLong(), null))
        assertFalse(validPeerTubeMp4(MediaKind.MP4, audio, audio.size.toLong(), null))
    }

    private fun metadata() = JSONObject().put("uuid", id).put("url", page).put("name", "Example recording")
        .put("privacy", JSONObject().put("id", 1)).put("state", JSONObject().put("id", 1))
        .put("downloadEnabled", true).put("isLocal", true).put("duration", 24)
        .put("files", JSONArray().put(file(360)).put(file(480).put("hasAudio", false)))
        .put("streamingPlaylists", JSONArray().put(JSONObject().put("files", JSONArray()
            .put(file(720)).put(file(1080).put("fileDownloadUrl", "https://mirror.example.net/download/1080.mp4"))
            .put(file(240).put("fileDownloadUrl", "$host/static/240.mp4")))))

    private fun file(height: Int) = JSONObject().put("height", height).put("size", 1234).put("hasAudio", true).put("hasVideo", true)
        .put("fileDownloadUrl", "$host/download/$height.mp4")
}
