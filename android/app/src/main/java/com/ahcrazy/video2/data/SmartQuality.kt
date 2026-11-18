package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.HlsVariant
import com.ahcrazy.video2.core.model.MediaKind

/** An actual file or advertised rendition, not a requested height that may silently downgrade. */
internal data class SmartQuality(val candidate: SmartCandidate, val rendition: HlsVariant? = null) {
    val media get() = candidate.media
    val height get() = (if (rendition == null) media.height else rendition.height)?.takeIf { it > 0 }
    val key get() = rendition?.let { "${media.url.length}:${media.url}:${it.id}" } ?: media.url

    fun matches(checked: DetectedMedia): Boolean = checked.downloadChecked() && checked.kind.isCompleteVideo &&
        !checked.drm.isProtected && checked.url == media.url &&
        (media.kind == MediaKind.OTHER || checked.kind == media.kind) &&
        (rendition == null || checked.checkedVariantUrl == rendition.url && checked.checkedAudioGroup == rendition.audioGroup.orEmpty()) &&
        (height == null || height == checked.height)

    fun isChecked(): Boolean = media.checkedAtMs != null && matches(media)
}

internal fun smartQualities(verified: SmartCandidate, others: List<SmartCandidate>): List<SmartQuality> =
    (listOf(verified) + others).distinctBy { it.media.url }.filter { it.media.isSmartCandidate() }
        .flatMap { candidate ->
            val media = candidate.media
            if (media.kind != MediaKind.HLS || media.variants.isEmpty()) listOf(SmartQuality(candidate))
            else media.variants.map { SmartQuality(candidate, it) }
        }.sortedWith(compareByDescending<SmartQuality> { it.height ?: 0 }.thenByDescending { it.isChecked() }
            .thenBy { it.media.kind == MediaKind.HLS }.thenBy { it.media.joinedTracks != null })
        // Keep distinct download methods but don't present duplicates of the same resolution and method.
        .distinctBy { Triple(it.height, it.media.kind, it.media.joinedTracks != null) }
        .take(16)
