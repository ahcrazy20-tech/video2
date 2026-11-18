package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.media.DownloadCooldown
import com.ahcrazy.video2.core.media.DownloadCooldowns
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.resolverEndpoint
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

/** One disclosed operator with two regions, not two independent quota pools. */
internal object SmartServers {
    const val privacy = "https://yt.chocolatemoo53.com/privacy"
    const val owner = "chocolatemoo53.com"
    val endpoints = listOf("https://yt.$owner", "https://eu.yt.$owner").map { checkNotNull(resolverEndpoint(it)) }

    // Include affiliated media hosts without trusting similarly named domains.
    fun cooldownGroup(host: String): String {
        val name = host.trimEnd('.')
        return when {
            name.endsWith(".$owner") -> owner
            name == "googlevideo.com" || name.endsWith(".googlevideo.com") || name.endsWith(".youtube.com") -> "youtube.com"
            else -> name
        }
    }
}

internal data class SmartCandidate(val media: DetectedMedia, val auth: DownloadAuth)
internal data class SmartDownloadRequest(
    val video: YouTubeVideo?, val title: String?, val direct: List<SmartCandidate> = emptyList(),
    val excludedUrls: Set<String> = emptySet(), val replaceFailedId: String? = null,
    val skipJoinedTracks: Boolean = false,
)

internal enum class SmartPhase { DIRECT, LOOKUP, MEDIA }
internal data class SmartStep(val phase: SmartPhase, val host: String? = null)

internal sealed interface SmartResult {
    data class Ready(val candidate: SmartCandidate) : SmartResult
    data class Unavailable(
        val media: MediaProblem? = null, val resolver: ResolverProblem? = null,
        val httpStatus: Int? = null, val protected: Boolean = false,
        val step: SmartStep? = null, val cooldown: DownloadCooldown? = null,
    ) : SmartResult
}

/** Check real routes on demand, remembering only the last working region (not its expiring media URLs).
 * A stats request can be throttled independently of media and cannot verify a download. Don't issue one.
 */
internal class SmartDownloads(
    private val resolve: suspend (HttpUrl, YouTubeVideo, Int, String?) -> List<DetectedMedia>,
    private val check: suspend (SmartCandidate, Int?) -> DetectedMedia,
    private val servers: List<HttpUrl> = SmartServers.endpoints,
    private val cooldowns: DownloadCooldowns = DownloadCooldowns(SmartServers::cooldownGroup),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val resolving = Mutex()
    private var lastWorking: Pair<Long, HttpUrl>? = null
    private var blockedStep: Pair<DownloadCooldown, SmartStep>? = null

    suspend fun find(
        request: SmartDownloadRequest, maxHeight: Int,
        permitServers: suspend () -> Boolean,
        progress: (SmartStep) -> Unit = {},
        onDirectChecked: (DetectedMedia) -> Unit = {},
        onRejectedUrl: (String) -> Unit = {},
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
        for (candidate in direct) {
            val host = httpMediaUrl(candidate.media.url)?.host
            val stage = SmartStep(SmartPhase.DIRECT, host)
            cooldowns.active(host)?.let { return limited(stage, it) }
            seen += candidate.media.url
            progress(stage)
            val checked = withTimeoutOrNull(7_000) { check(candidate, height) }
            if (checked != null) {
                onDirectChecked(checked)
                if (checked.probeProblem?.needsNewLink == true) onRejectedUrl(candidate.media.url)
                if (checked.isVerifiedVideo()) return SmartResult.Ready(candidate.copy(media = checked))
                if (checked.probeHttpStatus == 429 && host != null) {
                    return limited(stage, cooldowns.hold(host, checked.probeHost ?: host, null, checked.probeRetryAtMs))
                }
                failure = checked.unavailable(stage)
                if (checked.drm.isProtected || checked.probeProblem == MediaProblem.SIGN_IN) return failure
            } else failure = SmartResult.Unavailable(media = MediaProblem.NETWORK, step = stage)
        }
        request.direct.firstOrNull { it.media.probeProblem == MediaProblem.SIGN_IN }?.let { return it.media.unavailable(SmartStep(SmartPhase.DIRECT)) }
        val video = request.video ?: return failure
        if (!permitServers()) return failure

        return resolving.withLock {
            cooldowns.active(SmartServers.owner)?.let { pause ->
                return@withLock limited(blockedStep?.takeIf { it.first == pause }?.second ?: SmartStep(SmartPhase.LOOKUP), pause)
            }
            val preferred = lastWorking?.takeIf { nowMs() - it.first in 0..240_000 }?.second
            val order = (listOfNotNull(preferred) + servers).distinct().take(2)
            var externalFailure = failure
            for (server in order) {
                val lookup = SmartStep(SmartPhase.LOOKUP, server.host)
                progress(lookup)
                val found = try {
                    withTimeoutOrNull(12_000) { resolve(server, video, maxHeight, request.title) }
                        ?: throw ResolverFailure(ResolverProblem.NETWORK)
                } catch (e: ResolverFailure) {
                    if (e.problem == ResolverProblem.LIMIT || e.status == 429) {
                        val pause = cooldowns.hold(SmartServers.owner, e.host ?: server.host, e.retryAfterMillis, e.retryAtMs)
                        blockedStep = pause to lookup
                        return@withLock limited(lookup, pause)
                    }
                    externalFailure = SmartResult.Unavailable(resolver = e.problem, httpStatus = e.status,
                        protected = e.problem == ResolverProblem.PROTECTED, step = lookup)
                    if (e.problem in setOf(ResolverProblem.ACCESS, ResolverProblem.PROTECTED)) return@withLock externalFailure
                    continue
                }
                val stage = SmartStep(SmartPhase.MEDIA, server.host)
                progress(stage)
                val candidates = found.filter { it.isSmartCandidate() && it.url !in seen && it.joinedTracks?.audioUrl !in seen &&
                    (!request.skipJoinedTracks || it.joinedTracks == null) }
                    .distinctBy { it.url }.distinctBy(::representation).sortedBy(::representation)
                externalFailure = SmartResult.Unavailable(resolver = ResolverProblem.UNAVAILABLE, step = stage)
                val result = withTimeoutOrNull(25_000) {
                    for (media in candidates) {
                        if (!seen.add(media.url)) continue
                        val audio = media.joinedTracks?.audioUrl
                        if (audio != null && !seen.add(audio)) continue
                        val candidate = SmartCandidate(media, DownloadAuth.external.copy(rateLimitOwner = server.host))
                        val verified = check(candidate, height)
                        if (verified.isVerifiedVideo()) return@withTimeoutOrNull SmartResult.Ready(candidate.copy(media = verified))
                        if (verified.probeHttpStatus == 429) {
                            val pause = cooldowns.hold(SmartServers.owner, verified.probeHost ?: server.host, null, verified.probeRetryAtMs)
                            blockedStep = pause to stage
                            return@withTimeoutOrNull limited(stage, pause)
                        }
                        if (verified.probeProblem?.needsNewLink == true) onRejectedUrl(media.url)
                        externalFailure = verified.unavailable(stage)
                        if (verified.drm.isProtected || verified.probeProblem == MediaProblem.SIGN_IN) return@withTimeoutOrNull externalFailure
                    }
                    externalFailure
                } ?: SmartResult.Unavailable(media = MediaProblem.NETWORK, step = stage)
                if (result is SmartResult.Ready) {
                    lastWorking = nowMs() to server
                    return@withLock result
                }
                externalFailure = result as SmartResult.Unavailable
                if (result.protected || result.media == MediaProblem.SIGN_IN || result.httpStatus in setOf(401, 429)) return@withLock result
            }
            externalFailure
        }
    }

    private fun limited(step: SmartStep, pause: DownloadCooldown) = SmartResult.Unavailable(
        resolver = ResolverProblem.LIMIT.takeUnless { step.phase == SmartPhase.DIRECT },
        httpStatus = 429, step = step.copy(host = pause.host), cooldown = pause,
    )
}

// Prefer bounded complete-file transfers over hundreds of HLS segment requests, without changing signed URLs.
private fun representation(media: DetectedMedia): Int = when {
    media.kind == MediaKind.HLS -> 2
    media.joinedTracks != null -> 1
    else -> 0
}

internal fun DetectedMedia.isSmartCandidate() = !fromAd && canDownload && kind != MediaKind.DASH &&
    (kind.isCompleteVideo || kind == MediaKind.OTHER)

private fun DetectedMedia.isVerifiedVideo() = isSmartCandidate() && kind.isCompleteVideo && downloadChecked()
private fun DetectedMedia.unavailable(step: SmartStep) = SmartResult.Unavailable(
    media = probeProblem ?: MediaProblem.NOT_MEDIA, httpStatus = probeHttpStatus, protected = drm.isProtected,
    step = step.copy(host = probeHost ?: step.host))
private fun qualityBand(media: DetectedMedia, maxHeight: Int): Int {
    val height = media.height ?: return 1
    return if (maxHeight <= 0 || height <= maxHeight) 2 else 0
}
private fun qualityRank(media: DetectedMedia, maxHeight: Int): Int = (media.height ?: 0).let {
    if (maxHeight > 0 && it > maxHeight) -it else it
}
