package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.externalHttpsUrl
import com.ahcrazy.video2.core.media.isExpiredPlayerLink
import com.ahcrazy.video2.core.media.isMediaFragment
import com.ahcrazy.video2.core.media.origin
import com.ahcrazy.video2.core.media.publicMediaDns
import com.ahcrazy.video2.core.media.readMedia
import com.ahcrazy.video2.core.media.resolverEndpoint
import com.ahcrazy.video2.core.media.safeHeader
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
}

internal enum class ResolverProblem(val ar: String, val en: String) {
    ACCESS("الخادم رفض الطلب أو محتاج إذن من مالكه. اختار خادم عندك صلاحية عليه.", "The server refused the request or requires permission from its owner."),
    LIMIT("الخادم عليه حد للطلبات. استنى شوية قبل المحاولة.", "The server rate-limited this request. Wait before trying again."),
    UNAVAILABLE("الخادم ما قدّمش ملف فيديو كامل متاح للرابط ده.", "The server returned no available complete video for this link."),
    UNSUPPORTED("الخادم رجّع وسائط منفصلة أو رد مش مدعوم. محتاج ملف كامل أو قائمة HLS.", "The server returned separate tracks or an unsupported response. Use a complete file or HLS playlist."),
    NETWORK("تعذر الاتصال بالخادم. راجع عنوان الـAPI والاتصال.", "Couldn’t reach the server. Check its API address and your connection.");
    fun text(arabic: Boolean) = if (arabic) ar else en
}

internal class ResolverFailure(val problem: ResolverProblem) : IOException(problem.en)

/** One call to the selected, authorized host. No browser cookies, background fan-out, or redirects. */
internal class ExternalResolver(
    private val http: OkHttpClient = OkHttpClient.Builder().dns(publicMediaDns).build()
) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    suspend fun resolve(type: ResolverType, server: HttpUrl, video: YouTubeVideo, maxHeight: Int, titleHint: String?, apiKey: String?): List<DetectedMedia> {
        if (resolverEndpoint(server.toString()) != server) throw ResolverFailure(ResolverProblem.NETWORK)
        return try {
            withTimeoutOrNull(26_000) {
                client.readMedia(request(type, server, video, maxHeight, apiKey)) { response ->
                    if (!response.isSuccessful) throw ResolverFailure(when (response.code) {
                        401, 403 -> ResolverProblem.ACCESS
                        429 -> ResolverProblem.LIMIT
                        404, 410 -> ResolverProblem.UNAVAILABLE
                        else -> ResolverProblem.NETWORK
                    })
                    val source = response.body?.source() ?: throw ResolverFailure(ResolverProblem.UNSUPPORTED)
                    if (source.request(768 * 1024L + 1)) throw ResolverFailure(ResolverProblem.UNSUPPORTED)
                    val json = try { JSONObject(source.readUtf8()) } catch (_: org.json.JSONException) {
                        throw ResolverFailure(ResolverProblem.UNSUPPORTED)
                    }
                    parse(type, server, video, json, titleHint, maxHeight)
                }
            } ?: throw ResolverFailure(ResolverProblem.NETWORK)
        } catch (e: CancellationException) { throw e }
        catch (e: ResolverFailure) { throw e }
        catch (_: IOException) { throw ResolverFailure(ResolverProblem.NETWORK) }
    }

    internal fun request(type: ResolverType, server: HttpUrl, video: YouTubeVideo, maxHeight: Int, apiKey: String?): Request {
        val b = Request.Builder().header("Accept", "application/json")
        return when (type) {
            ResolverType.COBALT -> {
                apiKey.safeHeader(256)?.let { b.header("Authorization", "Api-Key $it") }
                val quality = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320).lastOrNull { it <= maxHeight } ?: 720
                val body = JSONObject().put("url", video.watchUrl).put("videoQuality", quality.toString())
                    .put("downloadMode", "auto").put("localProcessing", "disabled").put("youtubeVideoCodec", "h264")
                b.url(server).post(body.toString().toRequestBody("application/json".toMediaType())).build()
            }
            ResolverType.PIPED -> b.url(server.newBuilder().addPathSegment("streams").addPathSegment(video.id).build()).build()
            ResolverType.INVIDIOUS -> b.url(server.newBuilder().addPathSegments("api/v1/videos").addPathSegment(video.id)
                .addQueryParameter("fields", "title,lengthSeconds,formatStreams,hlsUrl,videoId,liveNow").build()).build()
        }
    }

    internal fun parse(type: ResolverType, server: HttpUrl, video: YouTubeVideo, json: JSONObject, titleHint: String?, maxHeight: Int): List<DetectedMedia> {
        if (json.optBoolean("drm") || json.optBoolean("isDRM")) throw ResolverFailure(ResolverProblem.UNAVAILABLE)
        if (json.optString("videoId").isNotEmpty() && json.optString("videoId") != video.id) throw ResolverFailure(ResolverProblem.UNAVAILABLE)
        val results = mutableListOf<DetectedMedia>()
        val fallbackTitle = titleHint?.take(160)?.ifBlank { null } ?: "YouTube · ${video.id}"
        val title = json.optString("title").take(160).ifBlank { fallbackTitle }
        val duration = json.optDouble("lengthSeconds").takeIf { it.isFinite() && it > 0 } ?: json.optDouble("duration").takeIf { it.isFinite() && it > 0 }
        fun add(raw: String, kind: MediaKind, label: String? = null, height: Int? = null, filename: String? = null, size: Long? = null) {
            val url = externalHttpsUrl(raw)?.takeUnless { it.isMediaFragment() || it.isExpiredPlayerLink() } ?: return
            if (kind == MediaKind.DASH || kind.isLikelyFragment || YouTubeVideo.parse(url.toString()) != null) return
            results += DetectedMedia(url.toString(), filename?.take(160)?.ifBlank { null } ?: title, kind,
                qualityLabel = label?.take(24), pageUrl = video.watchUrl, publicPageUrl = video.watchUrl,
                extractionMethod = type.method, durationSec = duration, height = height, byteSize = size)
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
                    val source = entry.optString("url")
                    val mime = entry.optString("mimeType").ifBlank { entry.optString("type") }
                    val kind = streamKind(source, mime, entry.optString("container").ifBlank { entry.optString("format") })
                    if (!kind.isCompleteVideo || kind == MediaKind.DASH) continue
                    val label = entry.optString("qualityLabel").ifBlank { entry.optString("quality") }
                    val height = entry.optInt("height").takeIf { it in 1..4320 } ?: label.takeWhile(Char::isDigit).toIntOrNull()?.takeIf { it in 1..4320 }
                    add(source, kind, label.ifBlank { null }, height, size = entry.optLong("contentLength").takeIf { it > 0 })
                }
                val hls = if (type == ResolverType.PIPED) json.optString("hls") else json.optString("hlsUrl")
                add(hls, MediaKind.HLS, "HLS")
            }
        }
        if (results.isEmpty()) throw ResolverFailure(ResolverProblem.UNAVAILABLE)
        return results.distinctBy { it.url }.sortedWith(compareByDescending<DetectedMedia> {
            val h = it.height ?: 0
            when { h > 0 && (maxHeight <= 0 || h <= maxHeight) -> 2; h == 0 -> 1; else -> 0 }
        }.thenByDescending { if (maxHeight > 0 && (it.height ?: 0) > maxHeight) -(it.height ?: 0) else (it.height ?: 0) }).take(12)
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
