package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.resolverEndpoint
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import com.ahcrazy.video2.data.ResolverFailure
import com.ahcrazy.video2.data.ResolverProblem
import com.ahcrazy.video2.data.SmartCandidate
import com.ahcrazy.video2.data.SmartDownloadRequest
import com.ahcrazy.video2.data.SmartDownloads
import com.ahcrazy.video2.data.SmartResult
import com.ahcrazy.video2.ui.browser.captureSmartPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmartDownloadsTest {
    private val video = YouTubeVideo.parse("https://m.youtube.com/watch?v=AbCdEfG_123&si=private")!!
    private val request = SmartDownloadRequest(video, "Test video")
    private val slow = resolverEndpoint("https://slow.example.net")!!
    private val fast = resolverEndpoint("https://fast.example.net")!!
    private val session = DownloadAuth("WebView", referer = video.watchUrl, useBrowserCookies = true)
    private val direct = file("https://site.example.net/movie.mp4", 720)

    @Test fun directVideoStartsWithoutLookingUpOrSharingAWatchId() = runBlocking<Unit> {
        val engine = SmartDownloads(
            ping = { error("a direct source must not contact a server") },
            resolve = { _, _, _, _ -> error("must not share") },
            check = { candidate, height ->
                assertEquals(session, candidate.auth)
                assertEquals(720, height)
                assertEquals(direct, candidate.media)
                verified(candidate.media)
            },
        )
        val result = engine.find(request.copy(direct = listOf(SmartCandidate(direct.copy(url = "https://site.example.net/1080.mp4", height = 1080), session),
            SmartCandidate(direct, session))), 720, permitServers = { error("no consent needed for direct downloads") }) as SmartResult.Ready
        assertEquals(direct.url, result.candidate.media.url)
    }

    @Test fun consentIsRequestedOnlyAfterFailureAndNoServerIsContactedWhenDeclined() = runBlocking<Unit> {
        val checked = mutableListOf<DetectedMedia>()
        val denied = direct.copy(probeProblem = MediaProblem.ACCESS, probeHttpStatus = 403)
        val engine = SmartDownloads(ping = { error("not opted in") }, resolve = { _, _, _, _ -> error("not opted in") },
            check = { candidate, _ -> assertEquals(direct, candidate.media); denied })
        val result = engine.find(request.copy(direct = listOf(SmartCandidate(direct, session))), 0, permitServers = {
            assertEquals(listOf(denied), checked)
            false
        }, onDirectChecked = checked::add) as SmartResult.Unavailable
        assertEquals(403, result.httpStatus)
    }

    @Test fun ranksByLatencyChecksActualMediaAndFallsBackToAnotherServer() = runBlocking<Unit> {
        val queried = mutableListOf<String>()
        val checked = mutableListOf<String>()
        val blocked = file("https://old.example.net/403.mp4")
        val engine = SmartDownloads(
            ping = { if (it == slow) delay(60) },
            resolve = { server, id, limit, title ->
                assertEquals(video, id)
                assertEquals(720, limit)
                assertEquals("Test video", title)
                queried += server.host
                if (server == fast) listOf(blocked, file("https://fast.example.net/denied.mp4"),
                    file("https://fast.example.net/less-useful.mp4"), file("https://fast.example.net/broken.m3u8", kind = MediaKind.HLS))
                else listOf(file("https://slow.example.net/ok.m3u8", kind = MediaKind.HLS))
            },
            check = { candidate, height ->
                assertEquals(DownloadAuth.external, candidate.auth)
                assertEquals(720, height)
                checked += candidate.media.url
                if (candidate.media.url.endsWith("ok.m3u8")) verified(candidate.media).copy(checkedVariantUrl = "https://slow.example.net/720.m3u8")
                else candidate.media.copy(probeProblem = MediaProblem.ACCESS, probeHttpStatus = 403)
            },
            servers = listOf(slow, fast),
        )
        val result = engine.find(request.copy(excludedUrls = setOf(blocked.url)), 720, permitServers = { true }) as SmartResult.Ready
        assertEquals(listOf(fast.host, slow.host), queried)
        assertEquals(listOf("https://fast.example.net/denied.mp4", "https://fast.example.net/broken.m3u8", "https://slow.example.net/ok.m3u8"), checked)
        assertEquals("https://slow.example.net/720.m3u8", result.candidate.media.checkedVariantUrl)
    }

    @Test fun respectsRateLimitsAcrossRegionsAndReusesOnlyTheIdFreeTiming() = runBlocking<Unit> {
        var time = 5_000L
        var probes = 0
        var requests = 0
        val engine = SmartDownloads(
            ping = { probes++ },
            resolve = { server, _, _, _ ->
                assertEquals(fast, server)
                requests++
                if (requests == 1) throw ResolverFailure(ResolverProblem.LIMIT, 429, 120_000)
                listOf(file("https://fast.example.net/$requests.mp4"))
            },
            check = { candidate, _ -> verified(candidate.media) }, servers = listOf(fast, slow), nowMs = { time },
        )
        assertEquals(429, (engine.find(request, 0, { true }) as SmartResult.Unavailable).httpStatus)
        assertEquals(1, requests)
        time += 90_000
        assertEquals(429, (engine.find(request, 0, { true }) as SmartResult.Unavailable).httpStatus)
        assertEquals(2, probes)
        time += 31_000
        val a = engine.find(request, 0, { true }) as SmartResult.Ready
        val b = engine.find(request, 0, { true }) as SmartResult.Ready
        assertNotEquals(a.candidate.media.url, b.candidate.media.url)
        assertEquals(4, probes) // Two fresh health measurements after cooldown, then use their ranking.
        assertEquals(3, requests)
    }

    @Test fun doesNotSearchOtherRegionsAfterTheApiRefusesAccessOrTheSiteRequiresLogin() = runBlocking<Unit> {
        var lookups = 0
        val engine = SmartDownloads(ping = {}, resolve = { _, _, _, _ -> lookups++; throw ResolverFailure(ResolverProblem.ACCESS, 403) },
            check = { c, _ -> c.media }, servers = listOf(fast, slow))
        val signIn = direct.copy(probeProblem = MediaProblem.SIGN_IN, probeHttpStatus = 401)
        val result = engine.find(request.copy(direct = listOf(SmartCandidate(signIn, session))), 0,
            permitServers = { error("site requires login") }) as SmartResult.Unavailable
        assertEquals(401, result.httpStatus)
        assertEquals(0, lookups)
        assertEquals(403, (engine.find(request, 0, { true }) as SmartResult.Unavailable).httpStatus)
        assertEquals(1, lookups)
    }

    @Test fun stoppingAnInFlightSearchNeverStartsAnAlternative() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        var resolves = 0
        val engine = SmartDownloads(ping = {}, resolve = { _, _, _, _ -> resolves++; started.complete(Unit); awaitCancellation() },
            check = { _, _ -> error("never checked") }, servers = listOf(fast, slow))
        val task = launch { engine.find(request, 0, { true }); error("cancelled request returned a result") }
        started.await()
        task.cancelAndJoin()
        assertEquals(1, resolves)
    }

    @Test fun captureDoesNotGuessBetweenPlayersOrSendTheSearchLink() {
        val search = "https://www.google.com/search?q=private"
        val embedded = direct.copy(pageUrl = search, publicPageUrl = null, requestReferer = "https://www.youtube.com/embed/${video.id}?opaque=ignored")
        val another = embedded.copy(url = "https://different.example.net/movie.mp4", title = "Other", requestReferer = "https://www.youtube.com/embed/xyzAbc_XYZ0")
        assertNull(captureSmartPlan(search, listOf(embedded, another)))
        val plan = captureSmartPlan(search, listOf(embedded, another.copy(fromAd = true)))!!
        assertEquals(video, plan.video)
        assertEquals(listOf(embedded), plan.media)
        assertEquals(listOf(embedded), captureSmartPlan(search, listOf(embedded, another), embedded)?.media)
        assertNull(captureSmartPlan(video.watchUrl, listOf(embedded.copy(drm = DRMKind.WIDEVINE))))
        assertEquals(video, captureSmartPlan(video.watchUrl, emptyList())?.video)
    }

    private fun file(url: String, height: Int? = 720, kind: MediaKind = MediaKind.MP4) =
        DetectedMedia(url, "Test video", kind, height = height, pageUrl = video.watchUrl)

    private fun verified(media: DetectedMedia) = media.copy(probed = true, checkedAtMs = System.currentTimeMillis())
}
