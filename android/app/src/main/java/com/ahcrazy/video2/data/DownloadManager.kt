package com.ahcrazy.video2.data

import android.content.Context
import android.media.MediaMetadataRetriever
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.DownloadJob
import com.ahcrazy.video2.core.model.DownloadState
import com.ahcrazy.video2.core.model.HlsVariant
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** HLS playlist helpers — port of HLSInspector. */
object Hls {
    fun parseMaster(text: String, baseUrl: String): List<HlsVariant> {
        val out = mutableListOf<HlsVariant>(); val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)
                val uri = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (uri.isNotEmpty()) out += HlsVariant(resolve(baseUrl, uri), bw,
                    res?.groupValues?.get(1)?.toIntOrNull(), res?.groupValues?.get(2)?.toIntOrNull())
                i++
            }
            i++
        }
        return out
    }
    fun parseMediaSegments(text: String, baseUrl: String): Pair<List<String>, String?> {
        val segs = mutableListOf<String>(); var keyUri: String? = null
        for (l in text.lines()) {
            if (l.startsWith("#EXT-X-KEY")) Regex("URI=\"([^\"]+)\"").find(l)?.let { keyUri = resolve(baseUrl, it.groupValues[1]) }
            else if (l.isNotBlank() && !l.startsWith("#")) segs += resolve(baseUrl, l.trim())
        }
        return segs to keyUri
    }
    fun resolve(base: String, rel: String): String = try {
        java.net.URL(java.net.URL(base), rel).toString()
    } catch (e: Exception) { rel }
    fun pickVariant(variants: List<HlsVariant>, maxHeight: Int?): HlsVariant? {
        if (variants.isEmpty()) return null
        val sorted = variants.sortedByDescending { it.height ?: 0 }
        if (maxHeight == null || maxHeight == 0) return sorted.first()
        return sorted.firstOrNull { (it.height ?: 0) <= maxHeight } ?: sorted.last()
    }
    fun isEncrypted(text: String) = text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")
    fun looksProtected(url: String, text: String) =
        url.lowercase().let { it.contains("drm") || it.contains("cbcs") || it.contains("license") } ||
            text.contains("cbcs", true) || text.contains("SAMPLE-AES", true)
}

/**
 * Download engine: direct files + parallel HLS, resumable queue persisted to disk —
 * the Android twin of DownloadManager.swift + HLSDownloader.swift.
 */
class DownloadManager(
    private val ctx: Context, private val library: LibraryStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<String, Job>()
    private val jobsFile = File(ctx.filesDir, "download-jobs.json")

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs

    init { loadJobs() }

    fun enqueue(media: DetectedMedia, auth: DownloadAuth? = null, maxHeight: Int? = null): DownloadJob {
        val job = if (media.drm.isProtected)
            DownloadJob(media = media, state = DownloadState.BLOCKED_DRM, auth = auth, preferredMaxHeight = maxHeight,
                errorMessage = "محتوى محمي بـ DRM (${media.drm.titleAr}) — التطبيق لا يكسر الحماية.")
        else DownloadJob(media = media, auth = auth, preferredMaxHeight = maxHeight)
        _jobs.value = listOf(job) + _jobs.value; persist()
        if (job.state != DownloadState.BLOCKED_DRM) pump(job.id)
        return job
    }

    fun enqueueManual(url: String, title: String, pageUrl: String?, auth: DownloadAuth? = null, maxHeight: Int? = null): DownloadJob {
        val kind = MediaKind.infer(url, null)
        val clean = title.ifBlank { url.substringAfterLast("/").substringBefore("?").ifBlank { "فيديو" } }
        return enqueue(DetectedMedia(url, clean, kind, pageUrl = pageUrl, extractionMethod = "manual"), auth, maxHeight)
    }

    fun jobMatching(url: String): DownloadJob? = _jobs.value.firstOrNull { it.media.url == url }

    fun cancel(id: String) { running.remove(id)?.cancel(); patch(id) { it.copy(state = DownloadState.PAUSED, errorMessage = null) } }
    fun resume(id: String) { patch(id) { it.copy(state = DownloadState.QUEUED, errorMessage = null) }; pump(id) }
    fun remove(id: String) { running.remove(id)?.cancel(); _jobs.value = _jobs.value.filter { it.id != id }; persist() }

    private fun pump(id: String) {
        if (running.containsKey(id)) return
        running[id] = scope.launch {
            patch(id) { it.copy(state = DownloadState.RUNNING) }
            try {
                val job = _jobs.value.first { it.id == id }
                val video = perform(job) { p, bytes -> patch(id) { it.copy(progress = p, bytesWritten = bytes) } }
                library.add(video)
                patch(id) { it.copy(state = DownloadState.COMPLETED, progress = 1.0, destPath = video.localPath) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) patch(id) { it.copy(state = DownloadState.PAUSED) }
                else patch(id) { it.copy(state = DownloadState.FAILED, errorMessage = e.message ?: "فشل التحميل") }
            } finally { running.remove(id) }
        }
    }

    private suspend fun perform(job: DownloadJob, onProgress: (Double, Long) -> Unit): SavedVideo {
        val media = job.media; val auth = job.auth ?: DownloadAuth.default
        return when (media.kind) {
            MediaKind.HLS -> downloadHls(job, auth, onProgress)
            else -> downloadFile(job, auth, onProgress)
        }
    }

    private fun request(url: String, auth: DownloadAuth): Request {
        val b = Request.Builder().url(url)
        auth.headers().forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    private suspend fun downloadFile(job: DownloadJob, auth: DownloadAuth, onProgress: (Double, Long) -> Unit): SavedVideo {
        val media = job.media
        val ext = media.kind.ext.takeIf { it != "bin" } ?: "mp4"
        val dest = File(library.videosDir, "${job.id}.$ext")
        http.newCall(request(media.url, auth)).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val body = res.body ?: throw IllegalStateException("رد فارغ")
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var read: Long = 0; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        if (n == 0) continue
                        out.write(buf, 0, n); read += n
                        onProgress(if (total > 0) read.toDouble() / total else 0.0, read)
                    }
                }
            }
        }
        return register(dest, job, dest.length())
    }

    private suspend fun downloadHls(job: DownloadJob, auth: DownloadAuth, onProgress: (Double, Long) -> Unit): SavedVideo {
        val media = job.media
        val masterText = getText(media.url, auth)
        if (Hls.looksProtected(media.url, masterText))
            throw IllegalStateException("بث محمي بـ DRM — لن يتم التحميل.")
        val variants = Hls.parseMaster(masterText, media.url)
        val chosen = Hls.pickVariant(variants, job.preferredMaxHeight)
        val mediaUrl = chosen?.url ?: media.url
        val mediaText = if (chosen != null) getText(mediaUrl, auth) else masterText
        val (segments, _) = Hls.parseMediaSegments(mediaText, mediaUrl)
        if (segments.isEmpty()) throw IllegalStateException("قائمة HLS فاضية")

        val folder = File(library.videosDir, job.id).apply { mkdirs() }
        val semaphore = Semaphore(4)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val written = java.util.concurrent.atomic.AtomicLong(0)
        kotlinx.coroutines.coroutineScope {
            segments.forEachIndexed { idx, segUrl ->
                launch {
                    semaphore.withPermit {
                        val out = File(folder, "seg-%05d.ts".format(idx))
                        if (!out.exists() || out.length() == 0L) {
                            http.newCall(request(segUrl, auth)).execute().use { res ->
                                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code} لجزء $idx")
                                res.body!!.byteStream().use { it.copyTo(out.outputStream()) }
                            }
                        }
                        written.addAndGet(out.length())
                        onProgress(done.incrementAndGet().toDouble() / segments.size, written.get())
                    }
                }
            }
        }
        // Local playable playlist (ExoPlayer plays it offline; original headers no longer needed)
        val local = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n")
            segments.indices.forEach { append("#EXTINF:10.0,\nseg-%05d.ts\n".format(it)) }
            append("#EXT-X-ENDLIST\n")
        }
        val playlist = File(folder, "index.m3u8").apply { writeText(local) }
        val total = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return register(playlist, job.copy(media = media.copy(kind = MediaKind.HLS)), total)
    }

    private fun getText(url: String, auth: DownloadAuth): String =
        http.newCall(request(url, auth)).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            res.body?.string() ?: throw IllegalStateException("رد فارغ")
        }

    private fun register(file: File, job: DownloadJob, size: Long): SavedVideo {
        var duration: Double? = null; var thumb: String? = null
        runCatching {
            val r = MediaMetadataRetriever(); r.setDataSource(file.absolutePath)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000)
            r.getFrameAtTime(1_000_000)?.let { bmp ->
                val tf = File(library.thumbsDir, "${job.id}.jpg")
                tf.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                thumb = tf.absolutePath
            }
            r.release()
        }
        return SavedVideo(
            id = job.id, title = job.media.title, sourceUrl = job.media.url, pageUrl = job.media.pageUrl,
            localPath = file.absolutePath, thumbnailPath = thumb,
            kind = if (file.extension == "m3u8") MediaKind.HLS else job.media.kind,
            durationSec = duration, fileSize = size, extractionMethod = job.media.extractionMethod
        )
    }

    @Synchronized private fun patch(id: String, f: (DownloadJob) -> DownloadJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) f(it) else it }; persist()
    }

    @Synchronized private fun persist() {
        runCatching {
            val arr = JSONArray()
            _jobs.value.forEach { j ->
                arr.put(JSONObject().apply {
                    put("id", j.id); put("state", j.state.name); put("progress", j.progress)
                    put("bytesWritten", j.bytesWritten); put("createdAt", j.createdAt)
                    put("errorMessage", j.errorMessage ?: JSONObject.NULL)
                    put("preferredMaxHeight", j.preferredMaxHeight ?: JSONObject.NULL)
                    put("destPath", j.destPath ?: JSONObject.NULL)
                    put("media", JSONObject().apply {
                        put("url", j.media.url); put("title", j.media.title); put("kind", j.media.kind.name)
                        put("pageUrl", j.media.pageUrl ?: JSONObject.NULL); put("method", j.media.extractionMethod)
                    })
                })
            }
            jobsFile.writeText(arr.toString())
        }
    }

    private fun loadJobs() {
        if (!jobsFile.exists()) return
        runCatching {
            val arr = JSONArray(jobsFile.readText())
            _jobs.value = (0 until arr.length()).map {
                val o = arr.getJSONObject(it); val m = o.getJSONObject("media")
                DownloadJob(
                    id = o.getString("id"),
                    state = runCatching { DownloadState.valueOf(o.getString("state")) }.getOrDefault(DownloadState.QUEUED)
                        .let { s -> if (s == DownloadState.RUNNING) DownloadState.PAUSED else s },
                    progress = o.optDouble("progress"), bytesWritten = o.optLong("bytesWritten"),
                    createdAt = o.optLong("createdAt"),
                    errorMessage = if (o.isNull("errorMessage")) null else o.optString("errorMessage"),
                    media = DetectedMedia(
                        url = m.getString("url"), title = m.getString("title"),
                        kind = runCatching { MediaKind.valueOf(m.getString("kind")) }.getOrDefault(MediaKind.OTHER),
                        pageUrl = if (m.isNull("pageUrl")) null else m.optString("pageUrl"),
                        extractionMethod = m.optString("method", "manual")
                    )
                )
            }
        }
    }
}

/** Foreground-service shell so long downloads survive the UI being swiped away. */
class DownloadService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val channel = android.app.NotificationChannel("downloads", "التحميلات", android.app.NotificationManager.IMPORTANCE_LOW)
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        val n = android.app.Notification.Builder(this, "downloads")
            .setContentTitle("فيديو ٢").setContentText("جارٍ التحميل في الخلفية…")
            .setSmallIcon(android.R.drawable.stat_sys_download).build()
        startForeground(42, n)
        return START_STICKY
    }
}
