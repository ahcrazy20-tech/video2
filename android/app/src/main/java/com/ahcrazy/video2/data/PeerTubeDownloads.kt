package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.media.externalHttpsUrl
import com.ahcrazy.video2.core.media.origin
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.MediaKind
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

@ConsistentCopyVisibility
internal data class PeerTubeVideo private constructor(val page: HttpUrl, val id: String) {
    val api: HttpUrl get() = page.newBuilder().encodedPath("/api/v1/videos/").addPathSegment(id).build()

    companion object {
        private val uuid = Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
        private val shortUuid = Regex("[1-9A-HJ-NP-Za-km-z]{22}")

        fun parse(url: String?): PeerTubeVideo? {
            val page = externalHttpsUrl(url) ?: return null
            val path = page.encodedPath.trim('/').split('/')
            val id = when {
                path.size == 2 && path[0] == "w" -> path[1]
                path.size == 3 && path.take(2) == listOf("videos", "watch") -> path[2]
                else -> return null
            }
            if (!uuid.matches(id) && !shortUuid.matches(id)) return null
            val normalizedId = if (uuid.matches(id)) id.lowercase() else id
            return PeerTubeVideo(page.newBuilder().encodedPath("/w/$normalizedId").query(null).build(), normalizedId)
        }
    }
}

/** Only the uploader's published, explicitly downloadable MP4s from the current site's official API. */
internal object PeerTubeDownloads {
    const val method = "publisher-peertube"

    fun parse(video: PeerTubeVideo, reply: JSONObject): List<DetectedMedia> {
        if (reply.optString("shortUUID") != video.id && !reply.optString("uuid").equals(video.id, ignoreCase = true)) {
            throw ResolverFailure(ResolverProblem.UNSUPPORTED)
        }
        if (reply.optJSONObject("privacy")?.optInt("id") != 1 || !reply.optBoolean("downloadEnabled") ||
            reply.optBoolean("blacklisted") || !reply.optBoolean("isLocal")) throw ResolverFailure(ResolverProblem.ACCESS)
        if (reply.optBoolean("isLive") || reply.optBoolean("waitTranscoding") || reply.optJSONObject("state")?.optInt("id") != 1) throw ResolverFailure(ResolverProblem.UNAVAILABLE)
        val duration = reply.optDouble("duration").takeIf { it.isFinite() && it > 0 } ?: throw ResolverFailure(ResolverProblem.UNAVAILABLE)
        val title = reply.optString("name").take(160).ifBlank { "PeerTube" }
        val canonical = PeerTubeVideo.parse(reply.optString("url"))?.takeIf {
            it.page.origin() == video.page.origin() && it.id == reply.optString("uuid").lowercase()
        } ?: video
        val files = entries(reply.optJSONArray("files")) + entries(reply.optJSONArray("streamingPlaylists"))
            .take(4).flatMap { entries(it.optJSONArray("files")) }
        return files.mapNotNull { file ->
            if (!file.optBoolean("hasAudio") || !file.optBoolean("hasVideo")) return@mapNotNull null
            val url = externalHttpsUrl(file.optString("fileDownloadUrl"))?.takeIf { it.origin() == video.page.origin() &&
                it.encodedPath.startsWith("/download/") && it.encodedPath.endsWith(".mp4", ignoreCase = true) } ?: return@mapNotNull null
            val height = file.optInt("height").takeIf { it in 1..4320 }
                ?: file.optJSONObject("resolution")?.optInt("id")?.takeIf { it in 1..4320 }
                ?: return@mapNotNull null
            DetectedMedia(url.toString(), title, MediaKind.MP4, qualityLabel = "${height}p", height = height,
                pageUrl = video.page.toString(), publicPageUrl = canonical.page.toString(), durationSec = duration,
                byteSize = file.optLong("size").takeIf { it > 0 }, extractionMethod = method, resolverHost = video.page.host)
        }.distinctBy { it.height }.sortedByDescending { it.height }.take(12)
            .ifEmpty { throw ResolverFailure(ResolverProblem.UNAVAILABLE) }
    }

    private fun entries(array: JSONArray?) = if (array == null) emptyList() else (0 until minOf(array.length(), 24)).mapNotNull(array::optJSONObject)
}

internal fun DetectedMedia.usesPublicMediaTransport() = ResolverType.forMedia(this) != null || extractionMethod == PeerTubeDownloads.method
