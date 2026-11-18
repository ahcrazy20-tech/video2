package com.ahcrazy.video2.data

import android.content.Context
import com.ahcrazy.video2.core.model.LibraryFolder
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON-index library store in app-private storage — same idea as iOS LibraryStore. */
class LibraryStore(ctx: Context) {
    val videosDir: File = File(ctx.filesDir, "Videos").apply { mkdirs() }
    val thumbsDir: File = File(ctx.filesDir, "Thumbs").apply { mkdirs() }
    val subtitlesDir: File = File(ctx.filesDir, "Subtitles").apply { mkdirs() }
    val dubsDir: File = File(ctx.filesDir, "Dubbing").apply { mkdirs() }
    private val indexFile = File(ctx.filesDir, "library.json")

    private val _videos = MutableStateFlow<List<SavedVideo>>(emptyList())
    val videos: StateFlow<List<SavedVideo>> = _videos
    private val _folders = MutableStateFlow<List<LibraryFolder>>(emptyList())
    val folders: StateFlow<List<LibraryFolder>> = _folders

    init { load() }

    fun load() {
        if (!indexFile.exists()) return
        runCatching {
            val root = JSONObject(indexFile.readText())
            val va = root.optJSONArray("videos") ?: JSONArray()
            _videos.value = (0 until va.length()).map { videoFromJson(va.getJSONObject(it)) }
                .sortedByDescending { it.createdAt }
            val fa = root.optJSONArray("folders") ?: JSONArray()
            _folders.value = (0 until fa.length()).map {
                val o = fa.getJSONObject(it)
                LibraryFolder(o.getString("id"), o.getString("name"), o.optLong("createdAt"))
            }
        }
    }

    @Synchronized fun save() {
        val root = JSONObject()
        root.put("videos", JSONArray().apply { _videos.value.forEach { put(videoToJson(it)) } })
        root.put("folders", JSONArray().apply {
            _folders.value.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt)) }
        })
        indexFile.writeText(root.toString())
    }

    @Synchronized fun add(video: SavedVideo) { _videos.value = listOf(video) + _videos.value.filterNot { it.id == video.id }; save() }
    fun update(video: SavedVideo) { _videos.value = _videos.value.map { if (it.id == video.id) video else it }; save() }
    fun byId(id: String): SavedVideo? = _videos.value.firstOrNull { it.id == id }

    fun delete(video: SavedVideo) {
        val f = File(video.localPath)
        if (video.kind == MediaKind.HLS || f.extension == "m3u8") f.parentFile?.deleteRecursively() else f.delete()
        video.thumbnailPath?.let { File(it).delete() }
        video.subtitleFiles.values.forEach { File(it).delete() }
        video.dubbedAudioPath?.let { File(it).delete() }
        _videos.value = _videos.value.filter { it.id != video.id }; save()
    }

    fun rename(video: SavedVideo, newTitle: String) = update(video.copy(title = newTitle))
    fun setFolder(video: SavedVideo, folderId: String?) = update(video.copy(folderId = folderId))
    fun updatePosition(video: SavedVideo, posSec: Double, durationSec: Double?) =
        update(video.copy(lastPositionSec = posSec, durationSec = durationSec ?: video.durationSec))
    fun createFolder(name: String) { _folders.value += LibraryFolder(name = name); save() }
    fun deleteFolder(folder: LibraryFolder) {
        _videos.value = _videos.value.map { if (it.folderId == folder.id) it.copy(folderId = null) else it }
        _folders.value = _folders.value.filter { it.id != folder.id }; save()
    }

    private fun videoToJson(v: SavedVideo) = JSONObject().apply {
        put("id", v.id); put("title", v.title); put("sourceUrl", v.sourceUrl)
        put("pageUrl", v.pageUrl ?: JSONObject.NULL); put("localPath", v.localPath)
        put("thumbnailPath", v.thumbnailPath ?: JSONObject.NULL); put("kind", v.kind.name)
        put("createdAt", v.createdAt); put("durationSec", v.durationSec ?: JSONObject.NULL)
        put("fileSize", v.fileSize); put("lastPositionSec", v.lastPositionSec)
        put("extractionMethod", v.extractionMethod); put("folderId", v.folderId ?: JSONObject.NULL)
        put("subtitleFiles", JSONObject(v.subtitleFiles as Map<*, *>))
        put("subtitleTargetLang", v.subtitleTargetLang ?: JSONObject.NULL)
        put("dubbedAudioPath", v.dubbedAudioPath ?: JSONObject.NULL)
    }

    private fun videoFromJson(o: JSONObject) = SavedVideo(
        id = o.getString("id"), title = o.getString("title"), sourceUrl = o.getString("sourceUrl"),
        pageUrl = o.optStringOrNull("pageUrl"), localPath = o.getString("localPath"),
        thumbnailPath = o.optStringOrNull("thumbnailPath"),
        kind = runCatching { MediaKind.valueOf(o.getString("kind")) }.getOrDefault(MediaKind.OTHER),
        createdAt = o.optLong("createdAt"), durationSec = if (o.isNull("durationSec")) null else o.optDouble("durationSec"),
        fileSize = o.optLong("fileSize"), lastPositionSec = o.optDouble("lastPositionSec"),
        extractionMethod = o.optString("extractionMethod"), folderId = o.optStringOrNull("folderId"),
        subtitleFiles = o.optJSONObject("subtitleFiles")?.let { j ->
            buildMap { j.keys().forEach { k -> put(k, j.getString(k)) } }
        } ?: emptyMap(),
        subtitleTargetLang = o.optStringOrNull("subtitleTargetLang"),
        dubbedAudioPath = o.optStringOrNull("dubbedAudioPath")
    )

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
}
