package com.ahcrazy.video2.ui.player

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import java.io.File

internal fun snapshotName() = "Kadr-${System.currentTimeMillis()}.jpg"

internal fun savePlayerSnapshot(ctx: Context, video: SavedVideo, positionMs: Long, document: Uri? = null): Boolean = runCatching {
    val reader = MediaMetadataRetriever()
    val image = try {
        reader.setDataSource(video.localPath)
        reader.getFrameAtTime(positionMs.coerceAtLeast(0L) * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
    } finally { reader.release() } ?: return false
    val resolver = ctx.contentResolver
    try {
        if (document != null) return resolver.openOutputStream(document)?.use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, snapshotName())
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Video2")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        var saved = false
        try {
            if (resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true) {
                saved = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1
            }
            saved
        } finally { if (!saved) resolver.delete(uri, null, null) }
    } finally { image.recycle() }
}.getOrDefault(false)

internal fun sharePlayerFile(ctx: Context, video: SavedVideo): Boolean = runCatching {
    val file = File(video.localPath)
    if (!file.isFile || video.kind == MediaKind.HLS) return false
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = ctx.contentResolver.getType(uri) ?: if (video.kind in setOf(MediaKind.AAC, MediaKind.MP3, MediaKind.WAV, MediaKind.M4A, MediaKind.OGG, MediaKind.FLAC)) "audio/*" else "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(ctx.contentResolver, video.title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(share, video.title))
    true
}.getOrDefault(false)
