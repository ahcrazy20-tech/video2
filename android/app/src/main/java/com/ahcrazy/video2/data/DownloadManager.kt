package com.ahcrazy.video2.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.os.IBinder
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import com.ahcrazy.video2.MainActivity
import com.ahcrazy.video2.Video2Application
import com.ahcrazy.video2.core.media.ContentRange
import com.ahcrazy.video2.core.media.MediaDownloadException
import com.ahcrazy.video2.core.media.MediaHttp
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.isMediaFragment
import com.ahcrazy.video2.core.media.isExpiredPlayerLink
import com.ahcrazy.video2.core.media.rangeNotAccepted
import com.ahcrazy.video2.core.media.matchesResume
import com.ahcrazy.video2.core.media.mediaValidator
import com.ahcrazy.video2.core.media.retryMediaRead
import com.ahcrazy.video2.core.media.requireMediaSuccess
import com.ahcrazy.video2.core.media.responseMediaKind
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.DownloadJob
import com.ahcrazy.video2.core.model.DownloadState
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import com.ahcrazy.video2.core.model.SavedVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Direct and HLS queue. Work files are private and never appear in the library before validation. */
class DownloadManager(private val ctx: Context, private val library: LibraryStore) {
    internal val http = MediaHttp()
    private val mediaProbe = MediaProbe(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<String, Job>()
    private val slots = Semaphore(2)
    private val jobsFile = AtomicFile(File(ctx.filesDir, "download-jobs.json"))
    private val workRoot = File(ctx.filesDir, "DownloadParts")
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs

    init { loadJobs() }

    @Synchronized
    fun enqueue(media: DetectedMedia, auth: DownloadAuth? = null, maxHeight: Int? = null, variantUrl: String? = null): DownloadJob {
        val external = ResolverType.forMedia(media) != null
        val requestAuth = if (external) DownloadAuth.external else auth
        _jobs.value.firstOrNull {
            it.media.url == media.url && it.state.isBusy && !media.drm.isProtected &&
                (it.auth?.externalHttpsOnly == true || ResolverType.forMedia(it.media) != null) == (requestAuth?.externalHttpsOnly == true)
        }?.let { return it }
        val job = DownloadJob(media = media, auth = requestAuth, preferredMaxHeight = maxHeight, preferredVariantUrl = variantUrl).let {
            when {
                media.drm.isProtected -> it.copy(state = DownloadState.BLOCKED_DRM, errorMessage = ProtectedMedia(media.drm).message)
                !media.canDownload -> it.copy(state = DownloadState.FAILED, errorMessage = media.probeProblem?.ar ?: unsupported(media).message, problem = media.probeProblem, httpStatus = media.probeHttpStatus)
                else -> it
            }
        }
        _jobs.value = listOf(job) + _jobs.value
        persist()
        if (job.state == DownloadState.QUEUED) {
            pump(job.id)
            startService()
        }
        return job
    }

    fun enqueueManual(url: String, title: String, pageUrl: String?, auth: DownloadAuth? = null, maxHeight: Int? = null): DownloadJob {
        val kind = MediaKind.infer(url, null)
        val clean = title.ifBlank { httpMediaUrl(url)?.pathSegments?.lastOrNull()?.ifBlank { "فيديو" } ?: "فيديو" }
        return enqueue(DetectedMedia(url, clean, kind, pageUrl = pageUrl, extractionMethod = "manual"), auth, maxHeight)
    }

    @Synchronized fun cancel(id: String) {
        if (_jobs.value.none { it.id == id && it.state.isBusy }) return
        patch(id) { it.copy(state = DownloadState.PAUSED, errorMessage = null) }
        running[id]?.cancel()
    }

    @Synchronized fun resume(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (!job.canRetry) return
        patch(id) { it.copy(state = DownloadState.QUEUED, errorMessage = null, problem = null, httpStatus = null) }
        pump(id)
        startService()
    }

    @Synchronized fun remove(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        _jobs.value = _jobs.value.filterNot { it.id == id }
        persist()
        val active = running[id]
        if (active != null) active.cancel()
        else if (job.state != DownloadState.COMPLETED) scope.launch { cleanUnfinished(id) }
    }

    private fun startService() {
        // The app also works in the foreground if Android disallows starting a service from this state.
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) }
    }

    @Synchronized private fun pump(id: String) {
        if (running.containsKey(id)) return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                slots.withPermit {
                    val job = synchronized(this@DownloadManager) {
                        val queued = _jobs.value.firstOrNull { it.id == id && it.state == DownloadState.QUEUED } ?: return@withPermit
                        patch(id) { it.copy(state = DownloadState.RUNNING) }
                        queued
                    }
                    val gate = AtomicLong(0)
                    val video = perform(job) { progress, bytes ->
                        val now = SystemClock.elapsedRealtime()
                        val last = gate.get()
                        if (now - last >= 300 && gate.compareAndSet(last, now)) {
                            patch(id, save = false) { if (it.state == DownloadState.RUNNING) it.copy(progress = progress, bytesWritten = bytes) else it }
                        }
                    }
                    ensureActive()
                    synchronized(this@DownloadManager) {
                        if (_jobs.value.any { it.id == id && it.state == DownloadState.RUNNING }) {
                            library.add(video)
                            patch(id) { it.copy(state = DownloadState.COMPLETED, progress = 1.0, bytesWritten = video.fileSize, destPath = video.localPath) }
                        }
                    }
                    workDir(id).deleteRecursively()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    if (e is ProtectedMedia) cleanUnfinished(id)
                    patch(id) { if (it.state != DownloadState.RUNNING) it else if (e is ProtectedMedia)
                        it.copy(state = DownloadState.BLOCKED_DRM, media = it.media.copy(drm = e.drm), errorMessage = e.message)
                    else {
                        val problem = (e as? MediaDownloadException)?.problem ?: if (e is IOException) MediaProblem.NETWORK else MediaProblem.BAD_RESPONSE
                        val detail = if (e is MediaDownloadException) e.message else problem.ar
                        it.copy(state = DownloadState.FAILED, errorMessage = detail, problem = problem, httpStatus = (e as? MediaDownloadException)?.status)
                    } }
                }
            }
        }
        running[id] = task
        task.invokeOnCompletion {
            val removed = synchronized(this@DownloadManager) {
                running.remove(id)
                val job = _jobs.value.firstOrNull { it.id == id }
                if (job?.state == DownloadState.QUEUED && scope.isActive) pump(id)
                job == null
            }
            if (removed) scope.launch { cleanUnfinished(id) }
        }
        task.start()
    }

    internal suspend fun probe(media: DetectedMedia, auth: DownloadAuth, preferredHeight: Int? = null): DetectedMedia =
        mediaProbe.check(media, if (ResolverType.forMedia(media) != null) DownloadAuth.external else auth, preferredHeight)

    private suspend fun perform(job: DownloadJob, progress: (Double, Long) -> Unit): SavedVideo {
        val media = job.media
        if (media.drm.isProtected) throw ProtectedMedia(media.drm)
        if (!media.canDownload) throw unsupported(media)
        val url = httpMediaUrl(media.url) ?: throw unsupported(media)
        if (url.isExpiredPlayerLink()) throw MediaDownloadException(MediaProblem.EXPIRED.ar, MediaProblem.EXPIRED)
        val auth = if (ResolverType.forMedia(media) != null) DownloadAuth.external
            else job.auth ?: DownloadAuth.default.copy(referer = media.requestReferer ?: media.pageUrl)
        val work = workDir(job.id).apply { mkdirs() }
        if (media.kind == MediaKind.HLS) return downloadHls(job, playlist(url, auth), auth, work, progress)
        return when (val file = retryMediaRead { downloadDirect(job, url, auth, work, progress) }) {
            is DirectResult.Playlist -> downloadHls(job, file.document, auth, work, progress)
            is DirectResult.FileReady -> finalizeDirect(job, file.part, file.kind)
            DirectResult.More, DirectResult.Restart -> error("Unexpected unfinished download")
        }
    }

    private sealed interface DirectResult {
        data class Playlist(val document: HlsDocument) : DirectResult
        data class FileReady(val part: File, val kind: MediaKind) : DirectResult
        data object More : DirectResult
        data object Restart : DirectResult
    }

    private suspend fun downloadDirect(job: DownloadJob, url: HttpUrl, auth: DownloadAuth, work: File, progress: (Double, Long) -> Unit): DirectResult {
        val part = File(work, "file.part")
        val metadata = File(work, "resume.json")
        var restarted = false
        var forcePlain = false
        repeat(128) {
            val resume = runCatching { JSONObject(metadata.readText()) }.getOrNull()
            val validator = resume?.text("validator")
            val savedKind = resume?.text("kind")?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
            val from = part.length().takeIf { !forcePlain && validator != null && savedKind != null } ?: 0L
            val request = http.request(url, auth).header("Accept-Encoding", "identity")
            if (from > 0) request.header("Range", "bytes=$from-").header("If-Range", validator!!)
            val result = http.read(request.build()) { response ->
                if (from > 0 && rangeNotAccepted(response.code) && !restarted) return@read DirectResult.Restart
                response.requireMediaSuccess()
                if (response.request.url.isMediaFragment()) throw MediaDownloadException(MediaProblem.NOT_MEDIA.ar, MediaProblem.NOT_MEDIA)
                val range = if (response.code == 206) ContentRange.parse(response.header("Content-Range")) else null
                val append = from > 0 && response.code == 206
                val expectedTotal = resume?.optLong("total")?.takeIf { it > 0 }
                if (append && (range?.matchesResume(from, expectedTotal) != true || resume?.text("url") != response.request.url.toString() ||
                        (response.mediaValidator() != null && response.mediaValidator() != validator))) return@read DirectResult.Restart
                if (response.code == 206 && !append && range?.matchesResume(0, null) != true) {
                    throw MediaDownloadException("الخادم أرسل جزءًا غير صالح من الملف.")
                }
                val body = response.body ?: throw MediaDownloadException("المصدر رجّع ردًا فاضيًا.")
                val kind = if (append) savedKind!! else responseMediaKind(response.request.url.toString(), response.header("Content-Type"),
                    response.peekBody(4096).bytes(), job.media.kind, requireEvidence = true)
                if (kind == MediaKind.HLS) return@read DirectResult.Playlist(checkedPlaylist(response))
                if (kind == MediaKind.DASH) {
                    if (response.peekBody(64 * 1024).string().contains("ContentProtection", true)) throw ProtectedMedia(DRMKind.UNKNOWN_PROTECTED)
                    throw unsupported(job.media.copy(kind = kind))
                }
                if (kind.isLikelyFragment || auth.externalHttpsOnly && !kind.isCompleteVideo) {
                    throw MediaDownloadException(MediaProblem.NOT_MEDIA.ar, MediaProblem.NOT_MEDIA)
                }
                val total = range?.total ?: body.contentLength().takeIf { it > 0 }
                if (!append) {
                    part.outputStream().close()
                    metadata.delete()
                    response.mediaValidator()?.let { v ->
                        metadata.writeText(JSONObject().put("kind", kind.name).put("validator", v).put("url", response.request.url.toString())
                            .put("total", total).toString())
                    }
                }
                val start = if (append) from else 0L
                val size = copyBody(body.byteStream(), part, append, range?.length ?: body.contentLength().takeIf { it >= 0 }) { count ->
                    val written = start + count
                    progress(if (total != null) (written.toDouble() / total).coerceIn(0.0, 1.0) else 0.0, written)
                }
                if (size <= 0) throw MediaDownloadException("الخادم رجّع ملف فاضي.")
                if (range != null && start + size != range.total) {
                    if (metadata.isFile) DirectResult.More else throw MediaDownloadException("الخادم قصّر النطاق من غير معرّف ثابت للملف.")
                } else DirectResult.FileReady(part, kind)
            }
            when (result) {
                DirectResult.More -> {
                    forcePlain = false
                    currentCoroutineContext().ensureActive()
                }
                DirectResult.Restart -> {
                    if (restarted) throw MediaDownloadException("تعذّر استئناف الملف من الخادم.")
                    // Keep any part until the plain GET is accepted. Don't delete it on a real denial.
                    restarted = true
                    forcePlain = true
                }
                else -> return result
            }
        }
        throw MediaDownloadException("الخادم قسّم الملف إلى عدد كبير جدًا من الأجزاء.")
    }

    private suspend fun playlist(url: HttpUrl, auth: DownloadAuth): HlsDocument = retryMediaRead {
        http.read(http.request(url, auth).build(), ::checkedPlaylist)
    }

    private suspend fun downloadHls(job: DownloadJob, master: HlsDocument, auth: DownloadAuth, work: File, progress: (Double, Long) -> Unit): SavedVideo {
        val (variant, video, audioTrack, audio) = selectHlsSource(master, job.preferredVariantUrl, job.preferredMaxHeight) { playlist(it, auth) }
        val resources = video.resources + audio?.resources.orEmpty()
        val localMaster = if (audio != null && audioTrack != null && variant != null) Hls.localMaster(variant, audioTrack) else video.text
        val folder = File(work, "hls")
        val stablePlan = File(folder, ".plan")
        // Do not reuse positional filenames when a live window, rendition, URL or byte range changes.
        val fingerprint = MessageDigest.getInstance("SHA-256").run {
            update(video.text.toByteArray()); update(audio?.text.orEmpty().toByteArray()); update(localMaster.toByteArray())
            resources.forEach { update("${it.name}\t${it.url}\t${it.range}\n".toByteArray()) }
            digest().joinToString("") { "%02x".format(it) }
        }
        if (runCatching { stablePlan.readText() }.getOrNull() != fingerprint) {
            folder.deleteRecursively()
            check(folder.mkdirs()) { "تعذّر إنشاء مجلد التحميل." }
            stablePlan.writeText(fingerprint)
        }
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        coroutineScope {
            repeat(minOf(resources.size, 6)) {
                launch {
                    while (true) {
                        val item = resources.getOrNull(next.getAndIncrement()) ?: break
                        ensureActive()
                        val dest = File(folder, item.name)
                        if (!dest.exists() || dest.length() <= 0L) {
                            val tmp = File(folder, "${item.name}.part")
                            val r = http.request(item.url, auth)
                            item.range?.let { r.header("Range", it.header).header("Accept-Encoding", "identity") }
                            retryMediaRead { http.read(r.build()) { response ->
                                response.requireMediaSuccess()
                                val body = response.body ?: throw MediaDownloadException("أحد أجزاء البث فاضي.")
                                val received = ContentRange.parse(response.header("Content-Range"))
                                if (item.range != null && (response.code != 206 || received == null || received.start != item.range.offset || received.end != item.range.last)) {
                                    throw MediaDownloadException("خادم HLS لم يُرسل نطاق البيانات المطلوب.")
                                }
                                if (item.range == null && response.code != 200) throw MediaDownloadException("الخادم أرسل جزء HLS غير مكتمل.")
                                if (response.header("Content-Type")?.contains("text/html", true) == true) throw MediaDownloadException("أحد أجزاء HLS رجع صفحة بدل الفيديو.")
                                val expected = if (item.key) 16L else item.range?.length ?: body.contentLength().takeIf { it >= 0 }
                                if (copyBody(body.byteStream(), tmp, false, expected) <= 0) throw MediaDownloadException("أحد أجزاء البث فاضي.")
                            } }
                            ensureActive()
                            if (!tmp.renameTo(dest)) throw MediaDownloadException("تعذّر حفظ جزء HLS.")
                        }
                        val totalBytes = bytes.addAndGet(dest.length())
                        progress(done.incrementAndGet().toDouble() / resources.size, totalBytes)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (audio != null) {
            File(folder, "video.m3u8").writeText(video.text)
            File(folder, "audio.m3u8").writeText(audio.text)
        }
        File(folder, "index.m3u8").writeText(localMaster)
        stablePlan.delete()
        val final = File(library.videosDir, job.id).apply { if (exists()) deleteRecursively() }
        if (!folder.renameTo(final)) throw MediaDownloadException("تعذّر نقل بث HLS للمكتبة.")
        return saved(File(final, "index.m3u8"), job, MediaKind.HLS, bytes.get(), video.duration)
    }

    private suspend fun copyBody(input: InputStream, file: File, append: Boolean, expected: Long?, progress: (Long) -> Unit = {}): Long {
        var count = 0L
        input.use { source -> file.outputStream(append).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                if (expected != null && read > expected - count) throw MediaDownloadException("حجم البيانات لا يطابق رد الخادم.")
                output.write(buffer, 0, read)
                count += read
                progress(count)
            }
        } }
        if (expected != null && count != expected) throw MediaDownloadException("الرد ناقص؛ جرّب الاستئناف.")
        return count
    }

    private fun File.outputStream(append: Boolean) = java.io.FileOutputStream(this, append).buffered()

    private fun finalizeDirect(job: DownloadJob, part: File, kind: MediaKind): SavedVideo {
        val extractor = MediaExtractor()
        val protected = try {
            extractor.setDataSource(part.absolutePath)
            extractor.drmInitData != null
        } catch (_: Exception) { false } finally { extractor.release() }
        if (protected) throw ProtectedMedia(DRMKind.UNKNOWN_PROTECTED)
        val dest = File(library.videosDir, "${job.id}.${kind.ext}")
        if (!part.renameTo(dest)) throw MediaDownloadException("تعذّر نقل الملف للمكتبة.")
        var duration: Double? = null
        var thumb: String? = null
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(dest.absolutePath)
                duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000)
                r.getFrameAtTime(1_000_000)?.let { bmp ->
                    val tf = File(library.thumbsDir, "${job.id}.jpg")
                    try { tf.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) } }
                    finally { bmp.recycle() }
                    thumb = tf.absolutePath
                }
            } finally { r.release() }
        }
        return saved(dest, job, kind, dest.length(), duration, thumb)
    }

    private fun saved(file: File, job: DownloadJob, kind: MediaKind, size: Long, duration: Double?, thumb: String? = null) = SavedVideo(
        id = job.id, title = job.media.title, sourceUrl = job.media.url, pageUrl = job.media.pageUrl,
        localPath = file.absolutePath, thumbnailPath = thumb, kind = kind,
        durationSec = duration?.takeIf { it > 0 } ?: job.media.durationSec, fileSize = size, extractionMethod = job.media.extractionMethod
    )

    private fun unsupported(media: DetectedMedia) = MediaDownloadException(when {
        media.kind == MediaKind.DASH -> "DASH متاح للتشغيل فقط؛ الحفظ بدون إنترنت غير مدعوم."
        media.isFragment -> "ده جزء من الفيديو، مش ملف كامل. حمّل رابط ملف الفيديو أو قائمة HLS."
        else -> "استخدم رابط فيديو أو صوت HTTP أو HTTPS صالح."
    })

    private fun workDir(id: String) = File(workRoot, id)

    private fun cleanUnfinished(id: String) {
        workDir(id).deleteRecursively()
        if (library.byId(id) == null) {
            library.videosDir.listFiles()?.filter { it.name == id || it.nameWithoutExtension == id }?.forEach { it.deleteRecursively() }
            File(library.thumbsDir, "$id.jpg").delete()
        }
    }

    @Synchronized private fun patch(id: String, save: Boolean = true, transform: (DownloadJob) -> DownloadJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) transform(it) else it }
        if (save) persist()
    }

    private fun persist() {
        runCatching {
            val list = JSONArray()
            _jobs.value.forEach { j ->
                list.put(JSONObject().apply {
                    put("id", j.id); put("state", j.state.name); put("progress", j.progress)
                    put("bytesWritten", j.bytesWritten); put("createdAt", j.createdAt)
                    put("errorMessage", j.errorMessage); put("problem", j.problem?.name); put("httpStatus", j.httpStatus)
                    put("preferredMaxHeight", j.preferredMaxHeight)
                    put("preferredVariantUrl", j.preferredVariantUrl); put("destPath", j.destPath)
                    put("media", JSONObject().apply {
                        put("url", j.media.url); put("title", j.media.title); put("kind", j.media.kind.name)
                        put("pageUrl", j.media.pageUrl); put("publicPageUrl", j.media.publicPageUrl); put("method", j.media.extractionMethod); put("drm", j.media.drm.name)
                        put("duration", j.media.durationSec)
                    })
                    // Auth configuration only. Session cookies are never serialized into this file.
                    j.auth?.takeUnless { it.externalHttpsOnly }?.let { put("auth", JSONObject().put("ua", it.userAgent).put("referer", it.referer)
                        .put("origin", it.origin).put("browser", it.useBrowserCookies).put("accept", it.accept)
                        .put("observed", it.observedResource == j.media.url)) }
                })
            }
            val out = jobsFile.startWrite()
            try { out.write(list.toString().toByteArray()); jobsFile.finishWrite(out) }
            catch (e: Exception) { jobsFile.failWrite(out); throw e }
        }
    }

    private fun loadJobs() {
        runCatching {
            val list = JSONArray(jobsFile.readFully().toString(Charsets.UTF_8))
            _jobs.value = (0 until list.length()).mapNotNull { i ->
                runCatching {
                    val j = list.getJSONObject(i); val m = j.getJSONObject("media")
                    val state = DownloadState.valueOf(j.getString("state"))
                    DownloadJob(
                        id = UUID.fromString(j.getString("id")).toString(),
                        state = if (state.isBusy) DownloadState.PAUSED else state,
                        progress = j.optDouble("progress", 0.0), bytesWritten = j.optLong("bytesWritten"),
                        createdAt = j.optLong("createdAt"), errorMessage = j.text("errorMessage"), destPath = j.text("destPath"),
                        problem = j.text("problem")?.let { runCatching { MediaProblem.valueOf(it) }.getOrNull() },
                        httpStatus = j.optInt("httpStatus").takeIf { it in 100..599 },
                        preferredMaxHeight = j.optInt("preferredMaxHeight").takeIf { it > 0 }, preferredVariantUrl = j.text("preferredVariantUrl"),
                        media = DetectedMedia(m.getString("url"), m.getString("title"), MediaKind.valueOf(m.getString("kind")),
                            pageUrl = m.text("pageUrl"), publicPageUrl = m.text("publicPageUrl"), extractionMethod = m.optString("method", "manual"),
                            drm = runCatching { DRMKind.valueOf(m.optString("drm")) }.getOrDefault(DRMKind.NONE),
                            durationSec = m.optDouble("duration").takeIf { it.isFinite() && it > 0 }),
                        auth = j.optJSONObject("auth")?.let { auth ->
                            val observed = auth.optBoolean("observed")
                            DownloadAuth(auth.optString("ua", DownloadAuth.CHROME_UA), auth.text("referer"), auth.text("origin"),
                                auth.optBoolean("browser"), auth.text("accept"), m.getString("url").takeIf { observed })
                        }
                    )
                }.getOrNull()
            }
        }
    }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
}

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val channelId = "downloads"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "التحميلات", NotificationManager.IMPORTANCE_LOW))
        startForeground(42, notification())
        if (observer?.isActive != true) observer = scope.launch {
            (application as Video2Application).downloads.jobs.collect { jobs ->
                val active = jobs.filter { it.state.isBusy }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else manager.notify(42, notification(active.size, active.first().progress))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(count: Int = 0, progress: Double = 0.0): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channelId).setContentTitle("فيديو ٢")
            .setContentText(if (count <= 1) "جارٍ التحميل في الخلفية…" else "جارٍ تحميل $count ملفات…")
            .setSmallIcon(android.R.drawable.stat_sys_download).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), count != 1 || progress == 0.0)
            .setContentIntent(open).build()
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}
