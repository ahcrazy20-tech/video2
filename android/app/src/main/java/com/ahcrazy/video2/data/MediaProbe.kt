package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.media.ContentRange
import com.ahcrazy.video2.core.media.DASH_ROOT
import com.ahcrazy.video2.core.media.MediaDownloadException
import com.ahcrazy.video2.core.media.MediaHttp
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.isMediaFragment
import com.ahcrazy.video2.core.media.requireMediaSuccess
import com.ahcrazy.video2.core.media.responseMediaKind
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.HlsVariant
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException

private const val SNIFF_BYTES = 4_096L
private const val PLAYLIST_LIMIT = 2L * 1024 * 1024

/** User-initiated metadata check. It never downloads the media or changes the source URL. */
internal class MediaProbe(private val http: MediaHttp) {
    suspend fun check(media: DetectedMedia, auth: DownloadAuth): DetectedMedia {
        val url = httpMediaUrl(media.url) ?: return media.failed(MediaProblem.NOT_MEDIA)
        if (media.drm.isProtected) return media
        if (media.isFragment) return media.failed(MediaProblem.NOT_MEDIA)
        return try {
            withTimeoutOrNull(12_000) {
                val known = if (media.kind == MediaKind.HLS || media.kind == MediaKind.DASH) media else sniff(media, url, auth)
                when (known.kind) {
                    MediaKind.HLS -> checkHls(known, url, auth)
                    MediaKind.DASH -> checkDash(known, url, auth)
                    else -> known
                }.copy(probed = true, probeProblem = null)
            } ?: media.failed(MediaProblem.NETWORK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectedMedia) {
            media.copy(drm = e.drm, probed = true, probeProblem = null)
        } catch (e: MediaDownloadException) {
            media.failed(e.problem)
        } catch (_: IOException) {
            media.failed(MediaProblem.NETWORK)
        }
    }

    private suspend fun sniff(media: DetectedMedia, url: HttpUrl, auth: DownloadAuth): DetectedMedia {
        for (useRange in listOf(true, false)) {
            val request = http.request(url, auth).header("Accept-Encoding", "identity")
            if (useRange) request.header("Range", "bytes=0-${SNIFF_BYTES - 1}")
            val check = http.read(request.build()) { response ->
                // A CDN can disallow HEAD or Range even though an ordinary GET works.
                if (useRange && response.code in setOf(400, 403, 405, 416, 501)) return@read null
                response.requireMediaSuccess()
                if (response.request.url.isMediaFragment()) throw notMedia()
                val type = response.header("Content-Type")
                val prefix = response.peekBody(SNIFF_BYTES).bytes()
                if (prefix.isEmpty()) throw notMedia()
                val range = if (response.code == 206) ContentRange.parse(response.header("Content-Range")) else null
                if (response.code == 206 && range?.start != 0L) throw MediaDownloadException("الخادم رجّع نطاقًا غير صالح.")
                val kind = responseMediaKind(response.request.url.toString(), type, prefix, media.kind, requireEvidence = true)
                if (kind.isLikelyFragment) throw notMedia()
                val size = if (response.code == 206) range?.total else response.body?.contentLength()?.takeIf { it > 0 }
                media.copy(kind = kind, mime = type ?: media.mime, byteSize = size ?: media.byteSize)
            }
            if (check != null) return check
        }
        throw notMedia()
    }

    private suspend fun checkHls(media: DetectedMedia, url: HttpUrl, auth: DownloadAuth): DetectedMedia {
        val master = http.read(http.request(url, auth).build(), ::checkedPlaylist)
        val variants = Hls.parseMaster(master.text, master.url.toString())
            .sortedWith(compareByDescending<HlsVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth })
        val top = variants.firstOrNull()
        if (top == null && Hls.isMaster(master.text)) throw MediaDownloadException("مفيش جودة صالحة في قائمة HLS.")
        val sample = if (top == null) master else http.read(http.request(httpMediaUrl(top.url)!!, auth).build(), ::checkedPlaylist)
        val plan = Hls.plan(sample, "video")
        top?.let { Hls.selectAudio(master, it) }?.url?.let { audio ->
            Hls.plan(http.read(http.request(audio, auth).build(), ::checkedPlaylist), "audio")
        }
        return media.copy(kind = MediaKind.HLS, variants = variants, byteSize = null,
            durationSec = media.durationSec ?: plan.duration.takeIf { it > 0 },
            height = media.height ?: top?.height, width = media.width ?: top?.width)
    }

    private suspend fun checkDash(media: DetectedMedia, url: HttpUrl, auth: DownloadAuth): DetectedMedia =
        http.read(http.request(url, auth).build()) { response ->
            val xml = response.boundedManifest()
            if (!DASH_ROOT.containsMatchIn(xml)) throw notMedia()
            if (xml.contains("ContentProtection", true)) throw ProtectedMedia(DRMKind.UNKNOWN_PROTECTED)
            media.copy(kind = MediaKind.DASH, byteSize = null)
        }

    private fun DetectedMedia.failed(problem: MediaProblem) = copy(probed = true, probeProblem = problem)
    private fun notMedia() = MediaDownloadException(MediaProblem.NOT_MEDIA.ar, MediaProblem.NOT_MEDIA)
}

internal fun checkedPlaylist(response: Response): HlsDocument {
    val text = response.boundedManifest()
    if (!text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")) {
        throw MediaDownloadException(MediaProblem.NOT_MEDIA.ar, MediaProblem.NOT_MEDIA)
    }
    Hls.validate(text)
    return HlsDocument(response.request.url, text)
}

private fun Response.boundedManifest(): String {
    requireMediaSuccess()
    if (code == 206) {
        val range = ContentRange.parse(header("Content-Range"))
        if (range == null || range.start != 0L || range.total != range.length) {
            throw MediaDownloadException("الخادم رجّع جزء من قائمة البث فقط.")
        }
    }
    val source = body?.source() ?: throw MediaDownloadException("قائمة البث فاضية.")
    if (source.request(PLAYLIST_LIMIT + 1)) throw MediaDownloadException("قائمة البث أكبر من الحد المسموح.")
    val text = source.readUtf8()
    if (text.isBlank() || text.trimStart().startsWith('<') && header("Content-Type")?.contains("text/html", true) == true) {
        throw MediaDownloadException(MediaProblem.NOT_MEDIA.ar, MediaProblem.NOT_MEDIA)
    }
    return text
}
