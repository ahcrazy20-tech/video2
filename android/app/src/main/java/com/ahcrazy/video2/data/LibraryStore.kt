package com.ahcrazy.video2.data

import android.content.Context
import android.util.AtomicFile
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
    private val indexFile = AtomicFile(File(ctx.filesDir, "library.json"))

    private val _videos = MutableStateFlow<List<SavedVideo>>(emptyList())
    val videos: StateFlow<List<SavedVideo>> = _videos
    private val _folders = MutableStateFlow<List<LibraryFolder>>(emptyList())
    val folders: StateFlow<List<LibraryFolder>> = _folders

    init { load() }

    @Synchronized fun load() {
        runCatching {
            val root = JSONObject(indexFile.openRead().bufferedReader().use { it.readText() })
            val va = root.optJSONArray("videos") ?: JSONArray()
            val videos = (0 until va.length()).map { videoFromJson(va.getJSONObject(it)) }
                .sortedByDescending { it.createdAt }
            val fa = root.optJSONArray("folders") ?: JSONArray()
            val folders = (0 until fa.length()).map {
                val o = fa.getJSONObject(it)
                LibraryFolder(o.getString("id"), o.getString("name"), o.optLong("createdAt"))
            }
            _videos.value = videos
            _folders.value = folders
        }
    }

    // Call only while holding the store lock; publish the new state after the complete index is written.
    private fun commit(videos: List<SavedVideo>, folders: List<LibraryFolder> = _folders.value) {
        val root = JSONObject()
        root.put("videos", JSONArray().apply { videos.forEach { put(videoToJson(it)) } })
        root.put("folders", JSONArray().apply {
            folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt)) }
        })
        val data = root.toString().toByteArray(Charsets.UTF_8)
        val out = indexFile.startWrite()
        try {
            out.write(data)
            indexFile.finishWrite(out)
        } catch (e: Exception) {
            indexFile.failWrite(out)
            throw e
        }
        _videos.value = videos
        _folders.value = folders
    }

    @Synchronized fun add(video: SavedVideo) = commit(listOf(video) + _videos.value.filterNot { it.id == video.id })
    @Synchronized fun update(video: SavedVideo) = commit(_videos.value.map { if (it.id == video.id) video else it })
    fun byId(id: String): SavedVideo? = _videos.value.firstOrNull { it.id == id }

    @Synchronized fun delete(item: SavedVideo) {
        val video = byId(item.id) ?: return
        commit(_videos.value.filter { it.id != video.id })
        val f = File(video.localPath)
        val parent = f.parentFile
        // A playlist imported as a regular file lives directly in Videos, not a download's bundle.
        if (video.kind == MediaKind.HLS && f.name == "index.m3u8" && parent != null && parent.parentFile == videosDir && parent.name == video.id) {
            parent.deleteRecursively()
        } else f.delete()
        video.thumbnailPath?.let { File(it).delete() }
        video.subtitleFiles.values.forEach { File(it).delete() }
        File(subtitlesDir, video.id).deleteRecursively()
        video.dubbedAudioPath?.let { File(it).delete() }
    }

    @Synchronized fun rename(video: SavedVideo, newTitle: String) {
        byId(video.id)?.let { update(it.copy(title = newTitle)) }
    }
    @Synchronized fun setFolder(video: SavedVideo, folderId: String?) {
        byId(video.id)?.let { update(it.copy(folderId = folderId)) }
    }
    @Synchronized fun updatePosition(video: SavedVideo, posSec: Double, durationSec: Double?) {
        byId(video.id)?.let {
            update(it.copy(lastPositionSec = posSec, durationSec = durationSec ?: it.durationSec,
                lastPlayedAt = System.currentTimeMillis()))
        }
    }
    @Synchronized internal fun attachSubtitles(id: String, path: String, files: Map<String, String>, target: String): Boolean {
        val video = byId(id)?.takeIf { it.localPath == path } ?: return false
        update(video.copy(subtitleFiles = files, subtitleTargetLang = target))
        return true
    }
    @Synchronized fun createFolder(name: String) = commit(_videos.value, _folders.value + LibraryFolder(name = name))
    @Synchronized fun deleteFolder(folder: LibraryFolder) {
        commit(_videos.value.map { if (it.folderId == folder.id) it.copy(folderId = null) else it },
            _folders.value.filter { it.id != folder.id })
    }

    private fun videoToJson(v: SavedVideo) = JSONObject().apply {
        put("id", v.id); put("title", v.title); put("sourceUrl", v.sourceUrl)
        put("pageUrl", v.pageUrl ?: JSONObject.NULL); put("localPath", v.localPath)
        put("thumbnailPath", v.thumbnailPath ?: JSONObject.NULL); put("kind", v.kind.name)
        put("createdAt", v.createdAt); put("durationSec", v.durationSec ?: JSONObject.NULL)
        put("fileSize", v.fileSize); put("lastPositionSec", v.lastPositionSec); put("lastPlayedAt", v.lastPlayedAt)
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
        dubbedAudioPath = o.optStringOrNull("dubbedAudioPath"), lastPlayedAt = o.optLong("lastPlayedAt")
    )

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
}
