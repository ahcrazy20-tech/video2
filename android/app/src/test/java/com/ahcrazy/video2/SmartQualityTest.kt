package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.DownloadCooldowns
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.HlsVariant
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import com.ahcrazy.video2.data.SmartCandidate
import com.ahcrazy.video2.data.SmartDownloadRequest
import com.ahcrazy.video2.data.SmartDownloads
import com.ahcrazy.video2.data.SmartQuality
import com.ahcrazy.video2.data.SmartResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmartQualityTest {
    private val source = "https://video.example.net"
    private val low = HlsVariant("$source/low", height = 360, audioGroup = "a")
    private val high = HlsVariant("$source/high", height = 720, audioGroup = "a")
    private val hls = DetectedMedia("$source/master", "Fixture", MediaKind.HLS)
    private val file = DetectedMedia("$source/file.mp4", "Fixture", MediaKind.MP4)
    private val watch = YouTubeVideo.fromId("AbCdEfG_123")!!
    private fun candidate(media: DetectedMedia) = SmartCandidate(media, DownloadAuth.default)
    private fun DetectedMedia.checked(rendition: HlsVariant? = null) = copy(checkedAtMs = System.currentTimeMillis(),
        height = rendition?.height ?: this.height, checkedVariantUrl = rendition?.url, checkedAudioGroup = rendition?.audioGroup)

    @Test fun aSingleDirectFileWithoutDimensionsDoesNotGetImaginaryQualities() = runBlocking<Unit> {
        val engine = SmartDownloads(resolve = { _, _, _, _ -> error("no service for direct video") }, check = { c, _ -> c.media.checked() })
        val result = engine.find(SmartDownloadRequest(null, file.title, listOf(candidate(file))), 1080,
            permitServers = { error("must not ask") }, offerQualities = true) as SmartResult.Ready
        assertEquals(listOf(null), result.qualities.map { it.height })
        assertEquals(file.url, result.qualities.single().media.url)
    }

    @Test fun listingResolutionsUsesTheSameSafeCandidateWithAllAdvertisedFileHeights() = runBlocking<Unit> {
        val highFile = file.copy(height = 1080)
        val lowFile = file.copy(url = "$source/360.mp4", height = 360)
        val catalogHeights = mutableListOf<Int>()
        val attempts = mutableListOf<String>()
        val engine = SmartDownloads(resolve = { _, _, height, _ ->
            catalogHeights += height
            listOf(highFile, lowFile)
        }, check = { c, _ -> attempts += c.media.url; c.media.checked() })
        val result = engine.find(SmartDownloadRequest(watch, file.title), 720, { true }, offerQualities = true) as SmartResult.Ready
        assertEquals(listOf(0), catalogHeights)
        assertEquals(listOf(lowFile.url), attempts)
        assertEquals(listOf(1080, 360), result.qualities.map { it.height })
        assertFalse(result.qualities.first().isChecked())
        assertTrue(result.qualities.last().isChecked())
        val exact = engine.verify(result.qualities.first()) as SmartResult.Ready
        assertEquals(highFile.url, exact.candidate.media.url)
        assertEquals(listOf(lowFile.url, highFile.url), attempts)
    }

    @Test fun anExplicitHlsPickRejectsBothADifferentResolutionAndADifferentAudioGroup() = runBlocking<Unit> {
        for (unexpected in listOf(low, high.copy(audioGroup = "other"))) {
            val engine = SmartDownloads(resolve = { _, _, _, _ -> error("must not look up substitutes") },
                check = { _, h -> assertEquals(360, h); hls.copy(variants = listOf(low, high)).checked(low) },
                checkExact = { _, rendition -> assertEquals(high, rendition); hls.checked(unexpected) })
            val catalog = engine.find(SmartDownloadRequest(null, hls.title, listOf(candidate(hls))), 360, { false },
                offerQualities = true) as SmartResult.Ready
            assertEquals(listOf(720, 360), catalog.qualities.map { it.height })
            assertTrue(catalog.qualities.last().isChecked())
            assertEquals(MediaProblem.NOT_MEDIA, (engine.verify(catalog.qualities.first()) as SmartResult.Unavailable).media)
        }
    }

    @Test fun manualQualityRespectsCooldownWithoutASecondReadOrALowerQuality() = runBlocking<Unit> {
        var reads = 0
        val engine = SmartDownloads(cooldowns = DownloadCooldowns(now = { 1_000 }), resolve = { _, _, _, _ -> error("no alternate route") },
            check = { c, _ -> reads++; c.media.copy(probeProblem = MediaProblem.TEMPORARY, probeHttpStatus = 429, probeRetryAtMs = 30_000L) })
        repeat(2) {
            val result = engine.verify(SmartQuality(candidate(file.copy(height = 720)))) as SmartResult.Unavailable
            assertEquals(429, result.httpStatus)
            assertEquals(30_000L, result.cooldown?.untilMs)
        }
        assertEquals(1, reads)
    }
}
