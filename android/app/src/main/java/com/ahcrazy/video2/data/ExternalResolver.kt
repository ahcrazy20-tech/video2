package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.media.DownloadCooldowns
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.externalHttpsUrl
import com.ahcrazy.video2.core.media.isExpiredPlayerLink
import com.ahcrazy.video2.core.media.isMediaFragment
import com.ahcrazy.video2.core.media.origin
import com.ahcrazy.video2.core.media.externalTransport
import com.ahcrazy.video2.core.media.readMedia
import com.ahcrazy.video2.core.media.resolverEndpoint
import com.ahcrazy.video2.core.media.retryAfterMillis
import com.ahcrazy.video2.core.media.safeHeader
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.JoinedTracks
import com.ahcrazy.video2.core.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal enum class ResolverType(val label: String, val docs: String) {
    COBALT("Cobalt", "https://github.com/imputnet/cobalt/blob/main/docs/run-an-instance.md"),
    PIPED("Piped", "https://docs.piped.video/docs/api-documentation/"),
    INVIDIOUS("Invidious", "https://docs.invidious.io/api/");
    val method get() = "external-${name.lowercase()}"

    companion object {
        fun forMedia(media: DetectedMedia): ResolverType? = entries.firstOrNull { it.method == media.extractionMethod }
    }
}

internal enum class ResolverProblem(val ar: String, val en: String) {
    ACCESS("الخادم رفض الطلب أو محتاج إذن من مالكه. اختار خادم عندك صلاحية عليه.", "The server refused the request or requires permission from its owner."),
    LIMIT("الخادم عليه حد للطلبات. استنى شوية قبل المحاولة.", "The server rate-limited this request. Wait before trying again."),
    PROTECTED("المصدر محمي أو محتاج شراء أو اشتراك.", "This source is protected or requires a purchase or subscription."),
    UNAVAILABLE("الخادم ما قدّمش ملف فيديو كامل متاح للرابط ده.", "The server returned no available complete video for this link."),
    UNSUPPORTED("الخادم رجّع وسائط منفصلة أو رد مش مدعوم. محتاج ملف كامل أو قائمة HLS.", "The server returned separate tracks or an unsupported response. Use a complete file or HLS playlist."),
    NETWORK("تعذر الاتصال بالخادم. راجع عنوان الـAPI والاتصال.", "Couldn’t reach the server. Check its API address and your connection.");
    fun text(arabic: Boolean) = if (arabic) ar else en
}

internal class ResolverFailure(
    val problem: ResolverProblem, val status: Int? = null, val retryAfterMillis: Long? = null, val host: String? = null,
    val retryAtMs: Long? = null,
) : IOException(problem.en)

/** One call to the selected, authorized host. No browser cookies, background fan-out, or redirects. */
internal class ExternalResolver(
    http: OkHttpClient = OkHttpClient(),
    private val cooldowns: DownloadCooldowns = DownloadCooldowns(SmartServers::cooldownGroup),
) {
    private val client = http.newBuilder().externalTransport().retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(24, TimeUnit.SECONDS).build()

    suspend fun resolve(type: ResolverType, server: HttpUrl, video: YouTubeVideo, maxHeight: Int, titleHint: String?, apiKey: String?,
                        includeSeparateTracks: Boolean = false): List<DetectedMedia> {
        if (resolverEndpoint(server.toString()) != server) throw ResolverFailure(ResolverProblem.NETWORK)
        cooldowns.active(server.host)?.let { throw ResolverFailure(ResolverProblem.LIMIT, 429, host = it.host, retryAtMs = it.untilMs) }
        return try {
            withTimeoutOrNull(26_000) {
                client.readMedia(request(type, server, video, maxHeight, apiKey, includeSeparateTracks)) { response ->
                    parse(type, server, video, response.resolverJson(768 * 1024L), titleHint, maxHeight, includeSeparateTracks)
                }
            } ?: throw ResolverFailure(ResolverProblem.NETWORK)
        } catch (e: CancellationException) { throw e }
        catch (e: ResolverFailure) {
            if (e.problem != ResolverProblem.LIMIT) throw e
            val pause = cooldowns.hold(server.host, e.host ?: server.host, e.retryAfterMillis)
            throw ResolverFailure(e.problem, 429, e.retryAfterMillis, pause.host, pause.untilMs)
        }
        catch (_: IOException) { throw ResolverFailure(ResolverProblem.NETWORK) }
    }

    private fun Response.resolverJson(limit: Long): JSONObject {
        if (!isSuccessful) throw ResolverFailure(when (code) {
            401, 403 -> ResolverProblem.ACCESS
            429 -> ResolverProblem.LIMIT
            400, 404, 410, 422 -> ResolverProblem.UNAVAILABLE
            405, 415 -> ResolverProblem.UNSUPPORTED
            else -> ResolverProblem.NETWORK
        }, code, retryAfterMillis(header("Retry-After")), request.url.host)
        val source = body?.source() ?: throw ResolverFailure(ResolverProblem.UNSUPPORTED)
        if (source.request(limit + 1)) throw ResolverFailure(ResolverProblem.UNSUPPORTED)
        return try { JSONObject(source.readUtf8()) } catch (_: org.json.JSONException) {
            throw ResolverFailure(ResolverProblem.UNSUPPORTED)
        }
    }

    internal fun request(type: ResolverType, server: HttpUrl, video: YouTubeVideo, maxHeight: Int, apiKey: String?,
                         includeSeparateTracks: Boolean = false): Request {
        val b = Request.Builder().header("Accept", "application/json")
        return when (type) {
            ResolverType.COBALT -> {
                apiKey.safeHeader(256)?.let { b.header("Authorization", "Api-Key $it") }
                val quality = if (maxHeight <= 0) "max" else listOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)
                    .lastOrNull { it <= maxHeight }?.toString() ?: "144"
                val body = JSONObject().put("url", video.watchUrl).put("videoQuality", quality)
                    .put("downloadMode", "auto").put("localProcessing", "disabled").put("youtubeVideoCodec", "h264")
                    .put("alwaysProxy", true)
                b.url(server).post(body.toString().toRequestBody("application/json".toMediaType())).build()
            }
            ResolverType.PIPED -> b.url(server.newBuilder().addPathSegment("streams").addPathSegment(video.id).build()).build()
            ResolverType.INVIDIOUS -> b.url(server.newBuilder().addPathSegments("api/v1/videos").addPathSegment(video.id)
                .addQueryParameter("local", "true")
                .addQueryParameter("fields", "title,lengthSeconds,formatStreams,hlsUrl,videoId,paid,premium" +
                    if (includeSeparateTracks) ",adaptiveFormats,liveNow,isUpcoming" else "").build()).build()
        }
    }

    internal fun parse(type: ResolverType, server: HttpUrl, video: YouTubeVideo, json: JSONObject, titleHint: String?, maxHeight: Int,
                       includeSeparateTracks: Boolean = false): List<DetectedMedia> {
        if (json.optBoolean("drm") || json.optBoolean("isDRM") || json.optBoolean("paid") || json.optBoolean("premium")) {
            throw ResolverFailure(ResolverProblem.PROTECTED)
        }
        // Some API instances express sign-in or private video refusals as a JSON error.
        if (type != ResolverType.COBALT) {
            val error = (json.optString("error") + " " + json.optString("message")).take(8192)
            if (listOf("LOGIN_REQUIRED", "SignInConfirmNotBot", "sign in", "private video").any { error.contains(it, true) }) {
                throw ResolverFailure(ResolverProblem.ACCESS)
            }
        }
        if (json.optString("videoId").isNotEmpty() && json.optString("videoId") != video.id) throw ResolverFailure(ResolverProblem.UNAVAILABLE)
        val results = mutableListOf<DetectedMedia>()
        val fallbackTitle = titleHint?.take(160)?.ifBlank { null } ?: "YouTube · ${video.id}"
        val title = json.optString("title").take(160).ifBlank { fallbackTitle }
        val duration = json.optDouble("lengthSeconds").takeIf { it.isFinite() && it > 0 } ?: json.optDouble("duration").takeIf { it.isFinite() && it > 0 }
        fun add(raw: String, kind: MediaKind, label: String? = null, height: Int? = null, filename: String? = null, size: Long? = null,
                tracks: JoinedTracks? = null) {
            val url = externalHttpsUrl(raw)?.takeUnless { it.isMediaFragment() || it.isExpiredPlayerLink() } ?: return
            if ((kind != MediaKind.OTHER && !kind.isCompleteVideo) || kind == MediaKind.DASH || YouTubeVideo.parse(url.toString()) != null) return
            results += DetectedMedia(url.toString(), filename?.take(160)?.ifBlank { null } ?: title, kind,
                qualityLabel = label?.take(24), pageUrl = video.watchUrl, publicPageUrl = video.watchUrl,
                extractionMethod = type.method, durationSec = duration, height = height, byteSize = size, resolverHost = server.host, joinedTracks = tracks)
        }
        when (type) {
            ResolverType.COBALT -> when (json.optString("status")) {
                "error" -> {
                    val error = json.optJSONObject("error")?.optString("code").orEmpty()
                    throw ResolverFailure(when {
                        error.startsWith("api.auth.") -> ResolverProblem.ACCESS
                        "rate" in error -> ResolverProblem.LIMIT
                        else -> ResolverProblem.UNAVAILABLE
                    })
                }
                "tunnel", "redirect" -> {
                    val target = externalHttpsUrl(json.optString("url")) ?: throw ResolverFailure(ResolverProblem.UNSUPPORTED)
                    if (json.optString("status") == "tunnel" && target.origin() != server.origin()) throw ResolverFailure(ResolverProblem.UNSUPPORTED)
                    val filename = json.optString("filename").substringAfterLast('/').substringAfterLast('\\')
                    val kind = MediaKind.infer(filename, null).takeUnless { it == MediaKind.OTHER } ?: MediaKind.infer(target.toString(), null)
                    add(target.toString(), kind, filename = filename)
                }
                else -> throw ResolverFailure(ResolverProblem.UNSUPPORTED)
            }
            ResolverType.PIPED, ResolverType.INVIDIOUS -> {
                val key = if (type == ResolverType.PIPED) "videoStreams" else "formatStreams"
                val muxed = json.optJSONArray(key) ?: if (type == ResolverType.PIPED) json.optJSONArray("video") else null
                for (entry in entries(muxed)) {
                    if (entry.optBoolean("videoOnly", type == ResolverType.PIPED) || entry.optBoolean("audioOnly") || entry.optBoolean("drm") || entry.optBoolean("isDRM")) continue
                    val source = mediaUrl(entry.optString("url"), server) ?: continue
                    val mime = entry.optString("mimeType").ifBlank { entry.optString("type") }
                    val kind = streamKind(source, mime, entry.optString("container").ifBlank { entry.optString("format") })
                    if (!kind.isCompleteVideo || kind == MediaKind.DASH) continue
                    val label = entry.optString("qualityLabel").ifBlank { entry.optString("quality") }
                    val height = entry.optInt("height").takeIf { it in 1..4320 } ?: label.takeWhile(Char::isDigit).toIntOrNull()?.takeIf { it in 1..4320 }
                    add(source, kind, label.ifBlank { null }, height, size = entry.optLong("contentLength").takeIf { it > 0 })
                }
                if (type == ResolverType.INVIDIOUS && includeSeparateTracks && duration != null &&
                    json.optString("videoId") == video.id && !json.optBoolean("liveNow") && !json.optBoolean("isUpcoming")) {
                    val pair = pairTracks(json.optJSONArray("adaptiveFormats"), server, maxHeight)
                    if (pair != null) {
                        val (picture, sound) = pair
                        val joined = JoinedTracks(sound.url, picture.bytes, sound.bytes)
                        add(picture.url, MediaKind.MP4, picture.height?.let { "${it}p" }, picture.height, size = joined.size, tracks = joined)
                    }
                }
                val hls = if (type == ResolverType.PIPED) json.optString("hls") else json.optString("hlsUrl")
                hlsUrl(type, hls, server)?.let { add(it, MediaKind.HLS, "HLS") }
            }
        }
        if (results.isEmpty()) throw ResolverFailure(ResolverProblem.UNAVAILABLE)
        return results.distinctBy { it.url }.sortedWith(compareByDescending<DetectedMedia> {
            val h = it.height ?: 0
            when { h > 0 && (maxHeight <= 0 || h <= maxHeight) -> 2; h == 0 -> 1; else -> 0 }
        }.thenByDescending { if (maxHeight > 0 && (it.height ?: 0) > maxHeight) -(it.height ?: 0) else (it.height ?: 0) }).take(12)
    }

    private data class Track(val url: String, val bytes: Long, val height: Int?, val bitrate: Long, val audio: Boolean)

    /** A documented complete-file pair, not media URLs constructed from itags, ranges or streaming fragments. */
    private fun pairTracks(data: JSONArray?, server: HttpUrl, maxHeight: Int): Pair<Track, Track>? {
        val tracks = entries(data).mapNotNull { entry ->
            if (entry.optBoolean("drm") || entry.optBoolean("isDRM")) return@mapNotNull null
            val mime = entry.optString("type").lowercase()
            val audio = mime.startsWith("audio/mp4;") && "mp4a.40." in mime
            if (!audio && !(mime.startsWith("video/mp4;") && "avc1." in mime)) return@mapNotNull null
            val url = externalHttpsUrl(mediaUrl(entry.optString("url"), server))?.takeIf {
                it.origin() == server.origin() && !it.isMediaFragment() && !it.isExpiredPlayerLink()
            } ?: return@mapNotNull null
            val size = entry.optLong("clen").takeIf { it in 1..JoinedTracks.MAX_TRACK_BYTES } ?: return@mapNotNull null
            val height = if (audio) null else entry.optString("qualityLabel").takeWhile(Char::isDigit).toIntOrNull()
                ?.takeIf { it in 1..4320 && (maxHeight <= 0 || it <= maxHeight) } ?: return@mapNotNull null
            Track(url.toString(), size, height, entry.optLong("bitrate"), audio)
        }.distinctBy { it.url }
        val video = tracks.filterNot { it.audio }.maxWithOrNull(compareBy<Track> { it.height }.thenBy { it.bitrate }) ?: return null
        val audio = tracks.filter { it.audio }.maxByOrNull { it.bitrate } ?: return null
        return video to audio
    }

    // Some deployments use an absolute URL; a root-relative target belongs to this trusted instance.
    private fun mediaUrl(raw: String, server: HttpUrl): String? =
        if (raw.startsWith('/') && !raw.startsWith("//")) server.resolve(raw)?.takeIf { it.origin() == server.origin() }?.toString()
        else externalHttpsUrl(raw)?.toString()

    private fun hlsUrl(type: ResolverType, raw: String, server: HttpUrl): String? {
        val url = externalHttpsUrl(mediaUrl(raw, server)) ?: return null
        val ownManifest = url.origin() == server.origin() && url.pathSegments.windowed(3).any { path ->
            path[0] == "api" && path[1] == "manifest" && path[2] in setOf("hls_variant", "hls_playlist")
        }
        // Invidious's video API proxies complete file URLs with local=true, but its HLS route
        // needs the flag independently to publish proxied video, audio and segment links.
        // Apply it only to the selected Invidious server's API, never to upstream media URLs.
        return if (type == ResolverType.INVIDIOUS && ownManifest) url.newBuilder().setQueryParameter("local", "true").build().toString()
        else url.toString()
    }

    private fun entries(data: JSONArray?) = if (data == null) emptyList() else (0 until minOf(data.length(), 48)).mapNotNull(data::optJSONObject)
    private fun streamKind(url: String, mime: String, container: String): MediaKind {
        if (mime.startsWith("audio/", true)) return MediaKind.OTHER
        return MediaKind.infer(url, mime).takeUnless { it == MediaKind.OTHER } ?: when (container.lowercase()) {
            "mpeg_4", "mp4" -> MediaKind.MP4
            "webm" -> MediaKind.WEBM
            "mkv" -> MediaKind.MKV
            else -> MediaKind.OTHER
        }
    }
}
