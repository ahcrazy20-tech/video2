package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.resolverEndpoint
import com.ahcrazy.video2.core.media.DownloadCooldowns
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.JoinedTracks
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import com.ahcrazy.video2.data.ResolverFailure
import com.ahcrazy.video2.data.ResolverProblem
import com.ahcrazy.video2.data.SmartCandidate
import com.ahcrazy.video2.data.SmartDownloadRequest
import com.ahcrazy.video2.data.SmartDownloads
import com.ahcrazy.video2.data.SmartPhase
import com.ahcrazy.video2.data.SmartResult
import com.ahcrazy.video2.data.SmartServers
import com.ahcrazy.video2.ui.browser.captureSmartPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
        val engine = SmartDownloads(resolve = { _, _, _, _ -> error("not opted in") },
            check = { candidate, _ -> assertEquals(direct, candidate.media); denied })
        val result = engine.find(request.copy(direct = listOf(SmartCandidate(direct, session))), 0, permitServers = {
            assertEquals(listOf(denied), checked)
            false
        }, onDirectChecked = checked::add) as SmartResult.Unavailable
        assertEquals(403, result.httpStatus)
    }

    @Test fun remembersAWorkingRegionWithoutMakingHealthRequests() = runBlocking<Unit> {
        val queried = mutableListOf<String>()
        val checked = mutableListOf<String>()
        val blocked = file("https://old.example.net/403.mp4")
        val engine = SmartDownloads(
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
                assertEquals(DownloadAuth.external, candidate.auth.copy(rateLimitOwner = null))
                assertEquals(720, height)
                checked += candidate.media.url
                if (candidate.media.url.endsWith("ok.m3u8")) verified(candidate.media).copy(checkedVariantUrl = "https://slow.example.net/720.m3u8")
                else candidate.media.copy(probeProblem = MediaProblem.ACCESS, probeHttpStatus = 403)
            },
            servers = listOf(fast, slow),
        )
        val result = engine.find(request.copy(excludedUrls = setOf(blocked.url)), 720, permitServers = { true }) as SmartResult.Ready
        assertEquals(listOf(fast.host, slow.host), queried)
        assertEquals(listOf("https://fast.example.net/denied.mp4", "https://fast.example.net/broken.m3u8", "https://slow.example.net/ok.m3u8"), checked)
        assertEquals("https://slow.example.net/720.m3u8", result.candidate.media.checkedVariantUrl)
        engine.find(request, 720, { true }) as SmartResult.Ready
        assertEquals(listOf(fast.host, slow.host, slow.host), queried)
    }

    @Test fun respectsTheActualLookupCooldownAcrossRegionsAndFreshAttempts() = runBlocking<Unit> {
        var time = 5_000L
        var requests = 0
        val cooldowns = DownloadCooldowns(SmartServers::cooldownGroup) { time }
        val engine = SmartDownloads(
            resolve = { server, _, _, _ ->
                assertEquals(fast, server)
                requests++
                if (requests == 1) throw ResolverFailure(ResolverProblem.LIMIT, 429, 120_000)
                listOf(file("https://fast.example.net/$requests.mp4"))
            },
            check = { candidate, _ -> verified(candidate.media) }, servers = listOf(fast, slow), cooldowns = cooldowns,
            nowMs = { time },
        )
        val limited = engine.find(request, 0, { true }) as SmartResult.Unavailable
        assertEquals(429, limited.httpStatus)
        assertEquals(SmartPhase.LOOKUP, limited.step?.phase)
        assertEquals(125_000L, limited.cooldown!!.untilMs)
        time += 90_000
        val repeated = engine.find(request.copy(title = "Another attempt"), 0, { true }) as SmartResult.Unavailable
        assertEquals(limited.cooldown, repeated.cooldown)
        assertEquals(1, requests)
        time += 30_001
        val a = engine.find(request, 0, { true }) as SmartResult.Ready
        val b = engine.find(request, 0, { true }) as SmartResult.Ready
        assertNotEquals(a.candidate.media.url, b.candidate.media.url)
        assertEquals(3, requests)
    }

    @Test fun aDirect429DoesNotShareTheVideoOrTryAnotherDirectCandidate() = runBlocking<Unit> {
        var checks = 0
        val req = request.copy(direct = listOf(SmartCandidate(direct, session),
            SmartCandidate(direct.copy(url = "https://second.example.net/file.mp4"), session)))
        val engine = SmartDownloads(resolve = { _, _, _, _ -> error("don't bypass the rate limit") },
            check = { c, _ -> checks++; c.media.copy(probeProblem = MediaProblem.TEMPORARY, probeHttpStatus = 429,
                probeHost = "origin.example.net", probeRetryAtMs = 20_000L) },
            cooldowns = DownloadCooldowns(now = { 5_000L }))
        repeat(2) {
            val limited = engine.find(req, 0, { error("must not ask to share") }) as SmartResult.Unavailable
            assertEquals(SmartPhase.DIRECT, limited.step?.phase)
            assertEquals("origin.example.net", limited.step?.host)
            assertEquals(20_000L, limited.cooldown!!.untilMs)
        }
        assertEquals(1, checks)
    }

    @Test fun triesPairedMp4BeforeHlsButMedia429StopsAllOtherRepresentationsAndRegions() = runBlocking<Unit> {
        val pair = file("https://fast.example.net/picture").copy(joinedTracks = JoinedTracks("https://fast.example.net/sound", 1000, 500))
        val hls = file("https://fast.example.net/master", kind = MediaKind.HLS)
        var requests = 0
        var checks = 0
        val engine = SmartDownloads(servers = listOf(fast, slow), cooldowns = DownloadCooldowns(now = { 5_000L }),
            resolve = { _, _, _, _ -> requests++; listOf(hls, pair) },
            check = { c, _ ->
                assertEquals(pair, c.media)
                checks++
                pair.copy(probeProblem = MediaProblem.TEMPORARY, probeHttpStatus = 429,
                    probeHost = "sound.example.net", probeRetryAtMs = 400_000L)
            })
        repeat(2) {
            val limited = engine.find(request, 0, { true }) as SmartResult.Unavailable
            assertEquals(SmartPhase.MEDIA, limited.step?.phase)
            assertEquals("sound.example.net", limited.step?.host)
            assertEquals(400_000L, limited.cooldown!!.untilMs)
        }
        assertEquals(1, checks)
        assertEquals(1, requests)
    }

    @Test fun unavailablePairedMp4CanFallBackToHlsFromTheSameReply() = runBlocking<Unit> {
        val pair = file("https://fast.example.net/picture").copy(joinedTracks = JoinedTracks("https://fast.example.net/sound", 1000, 500))
        val hls = file("https://fast.example.net/master", kind = MediaKind.HLS)
        val attempts = mutableListOf<String>()
        val engine = SmartDownloads(servers = listOf(fast, slow), resolve = { host, _, _, _ ->
            assertEquals(fast, host)
            listOf(hls, pair)
        }, check = { candidate, _ ->
            attempts += candidate.media.url
            if (candidate.media == pair) pair.copy(probeProblem = MediaProblem.NOT_MEDIA) else verified(hls)
        })
        val result = engine.find(request, 0, { true }) as SmartResult.Ready
        assertEquals(hls.url, result.candidate.media.url)
        assertEquals(listOf(pair.url, hls.url), attempts)
        attempts.clear()
        assertEquals(hls.url, (engine.find(request.copy(skipJoinedTracks = true), 0, { true }) as SmartResult.Ready).candidate.media.url)
        assertEquals(listOf(hls.url), attempts)
    }

    @Test fun doesNotSearchOtherRegionsAfterTheApiRefusesAccessOrTheSiteRequiresLogin() = runBlocking<Unit> {
        var lookups = 0
        val engine = SmartDownloads(resolve = { _, _, _, _ -> lookups++; throw ResolverFailure(ResolverProblem.ACCESS, 403) },
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
        val engine = SmartDownloads(resolve = { _, _, _, _ -> resolves++; started.complete(Unit); awaitCancellation() },
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
