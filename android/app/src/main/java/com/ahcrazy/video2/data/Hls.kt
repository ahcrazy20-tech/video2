package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.media.ByteWindow
import com.ahcrazy.video2.core.media.MediaDownloadException
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.HlsVariant
import com.ahcrazy.video2.core.model.MediaProblem
import okhttp3.HttpUrl

internal data class HlsDocument(val url: HttpUrl, val text: String)
internal data class HlsResource(val url: HttpUrl, val name: String, val range: ByteWindow? = null,
                                val key: Boolean = false, val initialization: Boolean = false)
internal data class HlsMediaPlan(val text: String, val resources: List<HlsResource>, val duration: Double, val ended: Boolean)
internal data class HlsAudio(val attributes: Map<String, String>, val url: HttpUrl?)
internal class ProtectedMedia(val drm: DRMKind) : java.io.IOException("بث محمي بـ DRM (${drm.titleAr}) — لن يتم التحميل.")

/** A local HLS package uses the original timing, sequence and transitions — not a generated TS-only playlist. */
internal object Hls {
    private val attrPattern = Regex("(?:^|,)\\s*([A-Z0-9-]+)\\s*=\\s*(\"[^\"]*\"|[^,]*)")
    private val resolutionOrder = compareBy<HlsVariant> { it.height ?: 0 }.thenBy { it.bandwidth }
    private val copyTags = setOf(
        "#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-DISCONTINUITY-SEQUENCE", "#EXT-X-DISCONTINUITY", "#EXT-X-PROGRAM-DATE-TIME",
        "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-I-FRAMES-ONLY", "#EXT-X-BITRATE", "#EXTINF"
    )

    fun parseMaster(text: String, baseUrl: String): List<HlsVariant> {
        val base = httpMediaUrl(baseUrl) ?: return emptyList()
        val out = mutableListOf<HlsVariant>()
        var pending: Map<String, String>? = null
        for (line in lines(text)) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attrs(line)
                !line.startsWith('#') -> {
                    val a = pending ?: continue
                    pending = null
                    val url = resolve(base, line) ?: continue
                    val resolution = a["RESOLUTION"]?.lowercase()?.split('x')
                    out += HlsVariant(url.toString(), a["BANDWIDTH"]?.toIntOrNull() ?: 0,
                        resolution?.getOrNull(0)?.toIntOrNull(), resolution?.getOrNull(1)?.toIntOrNull(), a["CODECS"], a["AUDIO"])
                }
            }
        }
        return out.distinctBy { it.url }
    }

    fun pickVariant(variants: List<HlsVariant>, maxHeight: Int?): HlsVariant? {
        val limit = maxHeight?.takeIf { it > 0 } ?: return variants.maxWithOrNull(resolutionOrder)
        val known = variants.filter { it.height != null }
        if (known.isEmpty()) return variants.maxWithOrNull(resolutionOrder)
        return known.filter { it.height!! <= limit }.maxWithOrNull(resolutionOrder)
            ?: known.minWithOrNull(compareBy<HlsVariant> { it.height }.thenByDescending { it.bandwidth })
    }

    fun isMaster(text: String) = lines(text).any { it.startsWith("#EXT-X-STREAM-INF:") }

    fun inspect(text: String): DRMKind {
        for (line in lines(text)) {
            if (!line.startsWith("#EXT-X-KEY:") && !line.startsWith("#EXT-X-SESSION-KEY:")) continue
            val a = attrs(line)
            val method = a["METHOD"]?.uppercase()
            val format = a["KEYFORMAT"].orEmpty().lowercase()
            val uri = a["URI"].orEmpty()
            if (method == "NONE") continue
            if (format.contains("apple.streamingkeydelivery") || format.contains("fairplay") || uri.startsWith("skd:", true)) return DRMKind.FAIRPLAY
            if (format.contains("widevine") || format.contains("edef8ba9")) return DRMKind.WIDEVINE
            if (format.contains("playready") || format.contains("9a04f079")) return DRMKind.PLAYREADY
            if (method != "AES-128" || (format.isNotEmpty() && format != "identity") ||
                (uri.substringBefore(':').matches(Regex("[a-zA-Z][a-zA-Z0-9+.-]*")) && ':' in uri && httpMediaUrl(uri) == null)) {
                return DRMKind.ENCRYPTED_HLS
            }
        }
        return DRMKind.NONE
    }

    fun validate(text: String) {
        if (lines(text).firstOrNull() != "#EXTM3U") throw MediaDownloadException("تعذّر قراءة قائمة HLS.")
        inspect(text).takeIf { it.isProtected }?.let { throw ProtectedMedia(it) }
        if (lines(text).any { it.startsWith("#EXT-X-SKIP:") || it.startsWith("#EXT-X-DEFINE:") }) {
            throw MediaDownloadException("قائمة HLS مختصرة أو فيها متغيرات غير مدعومة للحفظ.")
        }
    }

    fun selectAudio(doc: HlsDocument, variant: HlsVariant): HlsAudio? {
        val group = variant.audioGroup ?: return null
        val track = lines(doc.text).filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attrs)
            .filter { it["TYPE"] == "AUDIO" && it["GROUP-ID"] == group }.sortedByDescending { it["DEFAULT"] == "YES" }
            .firstOrNull() ?: throw MediaDownloadException("قائمة HLS بتشير لمسار صوت مش موجود.")
        val url = track["URI"]?.let { resolve(doc.url, it) ?: invalidResource() }
        return HlsAudio(track, url)
    }

    fun localMaster(variant: HlsVariant, audio: HlsAudio): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:6")
        val a = audio.attributes.toMutableMap().apply {
            put("GROUP-ID", "offline-audio"); put("DEFAULT", "YES"); put("AUTOSELECT", "YES"); put("URI", "audio.m3u8")
        }
        appendLine("#EXT-X-MEDIA:${serialize(a)}")
        val v = linkedMapOf("BANDWIDTH" to variant.bandwidth.coerceAtLeast(1).toString(), "AUDIO" to "offline-audio")
        if (variant.width != null && variant.height != null) v["RESOLUTION"] = "${variant.width}x${variant.height}"
        variant.codecs?.let { v["CODECS"] = it }
        appendLine("#EXT-X-STREAM-INF:${serialize(v)}")
        appendLine("video.m3u8")
    }

    fun plan(doc: HlsDocument, track: String): HlsMediaPlan {
        validate(doc.text)
        if (isMaster(doc.text)) throw MediaDownloadException("قائمة HLS متداخلة غير مدعومة.")
        val local = mutableListOf("#EXTM3U", "#EXT-X-PLAYLIST-TYPE:VOD")
        val resources = mutableListOf<HlsResource>()
        val inits = mutableMapOf<Pair<HttpUrl, ByteWindow?>, HlsResource>()
        var pendingRange: String? = null
        var previousSegment: HlsResource? = null
        var previousMap: HlsResource? = null
        var segmentDuration: Double? = null
        var total = 0.0
        var count = 0
        var ended = false
        for (line in lines(doc.text).drop(1)) {
            val tag = line.substringBefore(':')
            when {
                tag == "#EXT-X-KEY" -> {
                    val a = attrs(line).toMutableMap()
                    if (a["METHOD"].equals("NONE", true)) {
                        local += "#EXT-X-KEY:METHOD=NONE"
                    } else {
                        // Plain identity AES-128 is defined by HLS and uses the same authorized HTTP origin/session.
                        // validate() rejects all license-based methods before any key request is made.
                        val url = a["URI"]?.let { resolve(doc.url, it) } ?: invalidResource()
                        val resource = HlsResource(url, "$track-key-${resources.size}.bin", key = true)
                        resources += resource
                        a["URI"] = resource.name
                        local += "#EXT-X-KEY:${serialize(a)}"
                    }
                }
                tag == "#EXT-X-MAP" -> {
                    val a = attrs(line)
                    val url = a["URI"]?.let { resolve(doc.url, it) } ?: invalidResource()
                    val window = a["BYTERANGE"]?.let { byteRange(it, url, previousMap) }
                    val resource = inits.getOrPut(url to window) {
                        HlsResource(url, "$track-init-${inits.size}.mp4", window, initialization = true).also { resources += it }
                    }
                    previousMap = resource
                    local += "#EXT-X-MAP:URI=\"${resource.name}\""
                }
                tag == "#EXT-X-BYTERANGE" -> pendingRange = line.substringAfter(':')
                tag == "#EXT-X-ENDLIST" -> ended = true
                tag == "#EXT-X-GAP" -> throw MediaDownloadException("بعض أجزاء بث HLS غير متاحة في المصدر.")
                tag in copyTags -> {
                    if (tag == "#EXTINF") {
                        segmentDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it >= 0 } ?: invalidResource()
                    }
                    local += line
                }
                !line.startsWith('#') -> {
                    val duration = segmentDuration ?: invalidResource()
                    val url = resolve(doc.url, line) ?: invalidResource()
                    val window = pendingRange?.let { byteRange(it, url, previousSegment) }
                    val ext = url.pathSegments.last().substringAfterLast('.', "").lowercase()
                        .takeIf { it in setOf("ts", "mp4", "m4s", "cmfv", "cmfa", "aac", "mp3", "ac3", "ec3") }
                        ?: if (previousMap != null) "m4s" else "ts"
                    val resource = HlsResource(url, "$track-seg-${count++}.$ext", window)
                    previousSegment = resource
                    pendingRange = null
                    segmentDuration = null
                    resources += resource
                    local += resource.name
                    total += duration
                    if (resources.size > 20_000) throw MediaDownloadException("قائمة HLS أكبر من الحد المسموح.")
                }
                // Live control/partial tags don't apply to the finite offline snapshot.
            }
        }
        if (count == 0 || segmentDuration != null || local.none { it.startsWith("#EXT-X-TARGETDURATION:") }) invalidResource()
        local += "#EXT-X-ENDLIST"
        return HlsMediaPlan(local.joinToString("\n", postfix = "\n"), resources, total, ended)
    }

    fun duration(text: String): Double = lines(text).filter { it.startsWith("#EXTINF:") }
        .sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull()?.takeIf { n -> n.isFinite() && n > 0 } ?: 0.0 }

    private fun lines(text: String) = text.lineSequence().map { it.trim().trimStart('\uFEFF') }.filter { it.isNotEmpty() }
    private fun resolve(base: HttpUrl, value: String) = base.resolve(value)?.let { httpMediaUrl(it.toString()) }
    private fun attrs(line: String) = attrPattern.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim().removeSurrounding("\"") }
    private fun serialize(attrs: Map<String, String>) = attrs.entries.joinToString(",") { (k, v) ->
        if (k in setOf("BANDWIDTH", "RESOLUTION", "TYPE", "DEFAULT", "AUTOSELECT", "FORCED", "METHOD", "IV")) "$k=$v"
        else "$k=\"$v\""
    }

    private fun byteRange(raw: String, url: HttpUrl, previous: HlsResource?): ByteWindow {
        val len = raw.substringBefore('@').toLongOrNull()?.takeIf { it > 0 } ?: invalidResource()
        val offset = if ('@' in raw) raw.substringAfter('@').toLongOrNull()
            else previous?.takeIf { it.url == url }?.range?.let { it.last + 1 }
        if (offset == null || offset < 0 || offset > Long.MAX_VALUE - len) invalidResource()
        return ByteWindow(offset, len)
    }

    private fun invalidResource(): Nothing = throw MediaDownloadException("قائمة HLS ناقصة أو فيها مصدر غير صالح للحفظ.")
}

internal data class HlsSelection(
    val variant: HlsVariant?, val video: HlsMediaPlan, val audioTrack: HlsAudio?, val audio: HlsMediaPlan?
)

/** Auto can try other renditions the same playlist offers. A user's explicit choice is never changed. */
internal suspend fun selectHlsSource(
    master: HlsDocument,
    preferredVariantUrl: String? = null,
    preferredHeight: Int? = null,
    validateSelection: suspend (HlsSelection) -> Unit = {},
    load: suspend (HttpUrl) -> HlsDocument
): HlsSelection {
    Hls.validate(master.text)
    val variants = Hls.parseMaster(master.text, master.url.toString())
    if (Hls.isMaster(master.text) && variants.isEmpty()) throw MediaDownloadException("مفيش جودة صالحة في قائمة HLS.")
    val selected = if (preferredVariantUrl != null) variants.firstOrNull { it.url == preferredVariantUrl }
        ?: throw MediaDownloadException("الجودة المختارة مبقتش موجودة. اختارها تاني من صفحة المصدر.", MediaProblem.EXPIRED)
    else Hls.pickVariant(variants, preferredHeight)
    if (selected == null) return HlsSelection(null, Hls.plan(master, "video"), null, null).also { validateSelection(it) }
    val ordering = if (preferredVariantUrl != null) listOf(selected) else listOf(selected) + variants
        .filter { it.url != selected.url && (selected.height == null || (it.height ?: 0) <= selected.height) }
        .sortedWith(compareByDescending<HlsVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth }).take(2)
    var firstFailure: MediaDownloadException? = null
    for (variant in ordering) {
        try {
            val video = Hls.plan(load(httpMediaUrl(variant.url)!!), "video")
            val audioTrack = Hls.selectAudio(master, variant)
            val audio = audioTrack?.url?.let { Hls.plan(load(it), "audio") }
            return HlsSelection(variant, video, audioTrack, audio).also { validateSelection(it) }
        } catch (e: MediaDownloadException) {
            // Permission is not guessed. Only explicitly listed alternatives are considered.
            if (preferredVariantUrl != null || e.status == 429 || e.problem !in HLS_ALTERNATIVE_PROBLEMS) throw e
            if (firstFailure == null) firstFailure = e
        }
    }
    throw checkNotNull(firstFailure)
}

private val HLS_ALTERNATIVE_PROBLEMS = setOf(
    MediaProblem.ACCESS,
    MediaProblem.EXPIRED,
    MediaProblem.NOT_MEDIA,
    MediaProblem.TEMPORARY
)
