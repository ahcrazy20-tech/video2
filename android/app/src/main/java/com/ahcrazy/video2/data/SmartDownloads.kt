package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.resolverEndpoint
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

/** A small, disclosed list. The operator publishes these regions and invites third-party API clients.
 * No instance crawler, redirects for API calls, public Cobalt, or automatic use of old manual settings.
 */
internal object SmartServers {
    const val announcement = "https://www.chocolatemoo53.com/open-api-for-testing/"
    const val privacy = "https://yt.chocolatemoo53.com/privacy"
    val endpoints = listOf("https://yt.chocolatemoo53.com", "https://eu.yt.chocolatemoo53.com")
        .map { checkNotNull(resolverEndpoint(it)) }
}

internal data class SmartCandidate(val media: DetectedMedia, val auth: DownloadAuth)
internal data class SmartDownloadRequest(
    val video: YouTubeVideo?, val title: String?, val direct: List<SmartCandidate> = emptyList(),
    val excludedUrls: Set<String> = emptySet(), val replaceFailedId: String? = null,
)

internal sealed interface SmartStep {
    data object Direct : SmartStep
    data object Measuring : SmartStep
    data class Resolving(val host: String) : SmartStep
    data class Checking(val host: String) : SmartStep
}

internal sealed interface SmartResult {
    data class Ready(val candidate: SmartCandidate) : SmartResult
    data class Unavailable(
        val media: MediaProblem? = null, val resolver: ResolverProblem? = null,
        val httpStatus: Int? = null, val protected: Boolean = false,
    ) : SmartResult
}

/** One explicit action: try the site's sources first, then (if permitted) one ranked server at a time.
 * Speed is measured without a video ID; a fast JSON response alone can never win the media check.
 */
internal class SmartDownloads(
    private val ping: suspend (HttpUrl) -> Unit,
    private val resolve: suspend (HttpUrl, YouTubeVideo, Int, String?) -> List<DetectedMedia>,
    private val check: suspend (SmartCandidate, Int?) -> DetectedMedia,
    private val servers: List<HttpUrl> = SmartServers.endpoints,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    // All approved regions belong to one operator. Don't switch regions to work around rate limits.
    private val resolving = Mutex()
    private var blockedUntil: Long? = null
    private var ranked: Pair<Long, List<HttpUrl>>? = null

    suspend fun find(
        request: SmartDownloadRequest, maxHeight: Int,
        permitServers: suspend () -> Boolean,
        progress: (SmartStep) -> Unit = {},
        onDirectChecked: (DetectedMedia) -> Unit = {},
    ): SmartResult {
        if (request.direct.any { it.media.drm.isProtected }) return SmartResult.Unavailable(protected = true)
        val seen = request.excludedUrls.toMutableSet()
        request.direct.filter { it.media.probeProblem?.needsNewLink == true }.forEach { seen += it.media.url }
        var failure = SmartResult.Unavailable()
        val height = maxHeight.takeIf { it > 0 }
        val direct = request.direct.asSequence()
            .filter { it.media.isSmartCandidate() && it.media.url !in seen }
            .sortedWith(compareByDescending<SmartCandidate> { qualityBand(it.media, maxHeight) }
                .thenByDescending { it.media.extractionMethod == "webview-download" }
                .thenBy { it.media.kind == MediaKind.HLS }
                .thenByDescending { qualityRank(it.media, maxHeight) })
            .distinctBy { it.media.url }.take(2).toList()
        if (direct.isNotEmpty()) progress(SmartStep.Direct)
        for (candidate in direct) {
            seen += candidate.media.url
            val checked = withTimeoutOrNull(7_000) { check(candidate, height) }
            if (checked != null) {
                onDirectChecked(checked)
                if (checked.isVerifiedVideo()) return SmartResult.Ready(candidate.copy(media = checked))
                failure = checked.unavailable()
                // A sign-in, protection or throttling signal means stop, not change the route.
                if (checked.drm.isProtected || checked.probeHttpStatus in setOf(401, 429)) return failure
            } else failure = SmartResult.Unavailable(media = MediaProblem.NETWORK)
        }
        request.direct.firstOrNull { it.media.probeProblem == MediaProblem.SIGN_IN }?.let { return it.media.unavailable() }
        val video = request.video ?: return failure
        if (!permitServers()) return failure

        return resolving.withLock {
            if (blockedUntil?.let { nowMs() < it } == true) return@withLock rateLimited()
            progress(SmartStep.Measuring)
            val order = try { measure() } catch (e: ResolverFailure) {
                if (e.problem == ResolverProblem.LIMIT) defer(e.retryAfterMillis)
                return@withLock e.unavailable()
            }
            var externalFailure = failure
            for (server in order) {
                progress(SmartStep.Resolving(server.host))
                val found = try {
                    withTimeoutOrNull(7_000) { resolve(server, video, maxHeight, request.title) }
                        ?: throw ResolverFailure(ResolverProblem.NETWORK)
                } catch (e: ResolverFailure) {
                    externalFailure = e.unavailable()
                    if (e.problem == ResolverProblem.LIMIT) defer(e.retryAfterMillis)
                    if (e.problem in setOf(ResolverProblem.LIMIT, ResolverProblem.ACCESS, ResolverProblem.PROTECTED)) return@withLock externalFailure
                    continue
                }
                progress(SmartStep.Checking(server.host))
                val available = found.filter { it.isSmartCandidate() && it.url !in seen }.distinctBy { it.url }
                val first = available.firstOrNull()
                // If a file fails, try the server's HLS (or vice versa) before another version of the same format.
                val alternative = available.drop(1).firstOrNull { (it.kind == MediaKind.HLS) != (first?.kind == MediaKind.HLS) }
                    ?: available.getOrNull(1)
                val candidates = listOfNotNull(first, alternative)
                externalFailure = SmartResult.Unavailable(resolver = ResolverProblem.UNAVAILABLE)
                val result = withTimeoutOrNull(18_500) {
                    for (media in candidates) {
                        seen += media.url
                        val candidate = SmartCandidate(media, DownloadAuth.external)
                        val verified = check(candidate, height)
                        if (verified.isVerifiedVideo()) return@withTimeoutOrNull SmartResult.Ready(candidate.copy(media = verified))
                        externalFailure = verified.unavailable()
                        if (verified.probeHttpStatus == 429) defer(null)
                        if (verified.drm.isProtected || verified.probeHttpStatus in setOf(401, 429)) return@withTimeoutOrNull externalFailure
                    }
                    externalFailure
                } ?: SmartResult.Unavailable(media = MediaProblem.NETWORK)
                if (result is SmartResult.Ready) return@withLock result
                externalFailure = result as SmartResult.Unavailable
                if (result.protected || result.httpStatus in setOf(401, 429)) return@withLock result
            }
            externalFailure
        }
    }

    private suspend fun measure(): List<HttpUrl> {
        ranked?.takeIf { nowMs() < it.first }?.let { return it.second }
        val attempts = coroutineScope {
            servers.distinct().take(2).map { server -> async {
                val start = nowMs()
                try {
                    withTimeoutOrNull(2_500) { ping(server); true } ?: throw ResolverFailure(ResolverProblem.NETWORK)
                    Measurement(server, nowMs() - start)
                } catch (e: ResolverFailure) { Measurement(server, Long.MAX_VALUE, e) }
            } }.awaitAll()
        }
        attempts.mapNotNull { it.failure }.firstOrNull { it.problem == ResolverProblem.LIMIT }?.let { throw it }
        val online = attempts.filter { it.failure == null }.sortedBy { it.millis }.map { it.server }
        if (online.isEmpty()) throw (attempts.firstNotNullOfOrNull { it.failure?.takeIf { e -> e.problem == ResolverProblem.ACCESS } }
            ?: ResolverFailure(ResolverProblem.NETWORK))
        // Cache ID-free timing, not the provider's expiring media URLs. Failed probes get a short TTL.
        ranked = (nowMs() + if (online.size == attempts.size) 4 * 60_000L else 35_000L) to online
        return online
    }

    private fun defer(retryAfter: Long?) {
        blockedUntil = nowMs() + maxOf(60_000L, retryAfter ?: 0L)
        ranked = null
    }

    private data class Measurement(val server: HttpUrl, val millis: Long, val failure: ResolverFailure? = null)
    private fun rateLimited() = SmartResult.Unavailable(resolver = ResolverProblem.LIMIT, httpStatus = 429)
}

internal fun DetectedMedia.isSmartCandidate() = !fromAd && canDownload && kind != MediaKind.DASH &&
    (kind.isCompleteVideo || kind == MediaKind.OTHER)

private fun DetectedMedia.isVerifiedVideo() = isSmartCandidate() && kind.isCompleteVideo && downloadChecked()
private fun DetectedMedia.unavailable() = SmartResult.Unavailable(
    media = probeProblem ?: MediaProblem.NOT_MEDIA, httpStatus = probeHttpStatus, protected = drm.isProtected)
private fun ResolverFailure.unavailable() = SmartResult.Unavailable(
    resolver = problem, httpStatus = status, protected = problem == ResolverProblem.PROTECTED)
private fun qualityBand(media: DetectedMedia, maxHeight: Int): Int {
    val height = media.height ?: return 1
    return if (maxHeight <= 0 || height <= maxHeight) 2 else 0
}
private fun qualityRank(media: DetectedMedia, maxHeight: Int): Int = (media.height ?: 0).let {
    if (maxHeight > 0 && it > maxHeight) -it else it
}
