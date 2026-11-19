package com.ahcrazy.video2.ui.library

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

internal val MediaKind.isAudioFile: Boolean get() = when (this) {
    MediaKind.AAC, MediaKind.MP3, MediaKind.WAV, MediaKind.M4A, MediaKind.OGG, MediaKind.FLAC -> true
    else -> false
}

internal fun libraryResumeCandidate(videos: List<SavedVideo>): SavedVideo? = videos.asSequence().filter { video ->
    val position = video.lastPositionSec
    val duration = video.durationSec?.takeIf { it.isFinite() && it > 0 }
    position.isFinite() && position > 5 && (duration == null || duration - position > minOf(8.0, duration * .05))
}.maxByOrNull { it.lastPlayedAt.takeIf { time -> time > 0L } ?: it.createdAt }

internal data class LibraryArtwork(val file: File? = null, val frame: Bitmap? = null, val duration: Double? = null)

private data class ArtworkRequest(val path: String, val thumb: String?, val readDuration: Boolean)
private data class ArtKey(val request: ArtworkRequest, val length: Long, val changed: Long, val thumbChanged: Long)

private object LibraryFrames {
    // Don't run dozens of native video decoders while the user scrolls or runs offline tasks.
    @OptIn(ExperimentalCoroutinesApi::class)
    val io = Dispatchers.IO.limitedParallelism(1)
    private val cache = object : LruCache<ArtKey, LibraryArtwork>(12 * 1024 * 1024) {
        override fun sizeOf(key: ArtKey, value: LibraryArtwork) = maxOf(1024, value.frame?.allocationByteCount ?: 0)
    }

    fun read(request: ArtworkRequest): LibraryArtwork {
        val file = File(request.path)
        val thumb = request.thumb?.let(::File)?.takeIf { it.isAbsolute && it.isFile }
        if (!file.isAbsolute || !file.isFile || file.extension.lowercase() in setOf("m3u8", "mpd")) {
            return LibraryArtwork(file = thumb)
        }
        val key = ArtKey(request, file.length(), file.lastModified(), thumb?.lastModified() ?: -1L)
        cache.get(key)?.let { return it }
        val info = if (thumb != null && !request.readDuration) LibraryArtwork(file = thumb) else {
            var duration: Double? = null
            var frame: Bitmap? = null
            try {
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.absolutePath)
                    duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.takeIf { it > 0 }?.div(1000.0)
                    if (thumb == null && reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
                        val at = (duration?.times(160_000)?.toLong() ?: 0L).coerceIn(0L, 2_000_000L)
                        frame = if (Build.VERSION.SDK_INT >= 27) {
                            reader.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 384)
                        } else {
                            val w = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0L
                            val h = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0L
                            // API 26 can only return a full frame. Don't decode a huge bitmap for a cover.
                            if (w in 1..1920L && h in 1..1920L) reader.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                                val scale = minOf(640f / full.width, 384f / full.height, 1f)
                                Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1),
                                    (full.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== full) full.recycle() }
                            } else null
                        }
                    }
                } finally { reader.release() }
            } catch (_: Exception) {
                // Unsupported or missing files keep their media glyph, never a network image.
            }
            LibraryArtwork(thumb, frame, duration)
        }
        cache.put(key, info)
        return info
    }
}

@Composable
internal fun libraryArtwork(video: SavedVideo): State<LibraryArtwork> {
    val request = ArtworkRequest(video.localPath, video.thumbnailPath, video.durationSec?.let { it.isFinite() && it > 0 } != true)
    return produceState(LibraryArtwork(), request, video.kind) {
        value = withContext(LibraryFrames.io) {
            if (video.kind == MediaKind.HLS || video.kind == MediaKind.DASH) {
                LibraryArtwork(file = request.thumb?.let(::File)?.takeIf { it.isAbsolute && it.isFile })
            } else LibraryFrames.read(request)
        }
    }
}

@Composable
internal fun LibraryCover(video: SavedVideo, art: LibraryArtwork, modifier: Modifier = Modifier) {
    val frame = remember(art.frame) { art.frame?.asImageBitmap() }
    Box(modifier.background(Brush.linearGradient(listOf(V2.CardSoft, V2.Card))), contentAlignment = Alignment.Center) {
        Icon(if (video.kind.isAudioFile) Icons.Outlined.Headphones else Icons.Outlined.Movie, null,
            Modifier.size(25.dp), tint = V2.TextDim)
        if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else art.file?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
