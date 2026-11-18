package com.ahcrazy.video2.data.offline

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PinnedModel(val url: String, val bytes: Long, val sha256: String)
internal const val MIB = 1024L * 1024
private const val HF = "https://huggingface.co"
private const val WHISPER = "$HF/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"
internal const val RETIRED_TRANSLATOR_ID = "hymt2-7b-q6"
private const val RETIRED_FILE = "$RETIRED_TRANSLATOR_ID-88ef0aba5995.gguf"
private const val HYMT = "$HF/tencent/Hy-MT2-7B-GGUF/resolve/ab8472660ac61fac25f1af43fac2599d52a8a775"

internal enum class OfflineModel(
    val id: String, val label: String, val spec: PinnedModel, val extension: String,
    val minTotalRam: Long, val requiredFreeRam: Long
) {
    WHISPER_MEDIUM("whisper-medium", "Whisper Medium · F16",
        PinnedModel("$WHISPER/ggml-medium.bin", 1_533_763_059, "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208"),
        "bin", 5_120 * MIB, 2_400 * MIB),
    WHISPER_LARGE("whisper-large-v3", "Whisper large-v3 · F16",
        PinnedModel("$WHISPER/ggml-large-v3.bin", 3_095_033_483, "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2"),
        "bin", 7_168 * MIB, 4_300 * MIB),
    HYMT_Q4("hymt2-7b-q4", "Hy-MT2 7B · Q4_K_M",
        PinnedModel("$HYMT/Hy-MT2-7B-Q4_K_M.gguf", 4_624_648_896, "9f96256500f3fc1ab4d64336b58f52a949a95ad7516b0c229476eef782f9f77b"),
        "gguf", 7_168 * MIB, 5_000 * MIB);

    companion object {
        val speechModels = listOf(WHISPER_MEDIUM, WHISPER_LARGE)

        // Version 1.7 always used Large-v3, before jobs stored a speech model ID.
        fun speechForJob(id: String?): OfflineModel? = if (id == null) WHISPER_LARGE else speechModels.firstOrNull { it.id == id }

        fun pack(speech: OfflineModel): List<OfflineModel> {
            require(speech in speechModels)
            return listOf(speech, HYMT_Q4)
        }
    }
}

internal data class ModelState(val downloaded: Long, val installed: Boolean)

internal class OfflineModels(private val context: Context, private val work: OfflineWork) {
    private val root = File(context.noBackupFilesDir, "offline-models").apply { mkdirs() }
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val http = OkHttpClient.Builder().followSslRedirects(false)
        .connectTimeout(25, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val _models = MutableStateFlow(status())
    val models = _models.asStateFlow()
    private val _issue = MutableStateFlow<OfflineIssue?>(null)
    val issue = _issue.asStateFlow()

    // Keep existing installations usable without a new download. New users start at Medium.
    val defaultSpeech: OfflineModel
        get() = OfflineModel.speechModels.firstOrNull { ready(it) && compatible(it) } ?: OfflineModel.WHISPER_MEDIUM

    fun compatible(model: OfflineModel): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
        !manager.isLowRamDevice && memory().totalMem >= model.minTotalRam

    fun ready(model: OfflineModel) = file(model).let { it.isFile && it.length() == model.spec.bytes }

    fun path(model: OfflineModel): File {
        if (!ready(model)) throw OfflineFailure(OfflineIssue.MISSING_MODELS)
        if (!compatible(model)) throw OfflineFailure(OfflineIssue.NOT_SUPPORTED)
        val ram = memory()
        if (ram.lowMemory || ram.availMem < model.requiredFreeRam) {
            throw OfflineFailure(if (model == OfflineModel.WHISPER_LARGE) OfflineIssue.LARGE_MODEL_MEMORY else OfflineIssue.MEMORY)
        }
        return file(model)
    }

    fun downloadPack(speech: OfflineModel) {
        val pack = OfflineModel.pack(speech)
        if (work.status.value != null) return
        _issue.value = null
        if (pack.any { !compatible(it) }) { _issue.value = OfflineIssue.NOT_SUPPORTED; return }
        val total = pack.sumOf { it.spec.bytes }
        try {
            work.submit(OfflineWorkKind.DOWNLOAD, speech.id, onFinish = { error ->
                _models.value = status()
                if (error != null && !OfflineWork.cancelled(error)) {
                    _issue.value = (error as? OfflineFailure)?.issue ?: OfflineIssue.DOWNLOAD
                }
            }) { signal, notify ->
                root.mkdirs()
                val needed = pack.filterNot(::ready).sumOf { it.spec.bytes - partial(it).length().coerceIn(0, it.spec.bytes) }
                if (needed > 0 && root.usableSpace < needed + 256 * MIB) throw OfflineFailure(OfflineIssue.STORAGE)
                var done = 0L
                for (model in pack) {
                    signal.throwIfCanceled()
                    if (!ready(model)) {
                        try {
                            downloadModel(http, model.spec, partial(model), file(model), signal) { count ->
                                _models.value = _models.value + (model to ModelState(count, false))
                                notify((done + count).toDouble() / total)
                            }
                        } catch (e: IOException) {
                            if (root.usableSpace < 128 * MIB) throw OfflineFailure(OfflineIssue.STORAGE, e)
                            throw OfflineFailure(OfflineIssue.DOWNLOAD, e)
                        }
                        _models.value = status()
                    }
                    done += model.spec.bytes
                    notify(done.toDouble() / total)
                }
            }
        } catch (e: OfflineFailure) { _issue.value = e.issue }
    }

    fun remove(model: OfflineModel) {
        if (work.status.value != null) return
        _issue.value = null
        if (listOf(file(model), partial(model)).any { it.exists() && !it.delete() }) _issue.value = OfflineIssue.SAVE
        _models.value = status()
    }

    // A previous installation may have these files. Never load or download them again;
    // only remove them if the user requests it, without touching jobs or subtitles.
    fun retiredBytes() = retiredFiles().sumOf(File::length)

    fun removeRetired() {
        if (work.status.value != null) return
        _issue.value = null
        if (retiredFiles().map { !it.exists() || it.delete() }.any { !it }) _issue.value = OfflineIssue.SAVE
    }

    private fun retiredFiles() = listOf(File(root, RETIRED_FILE), File(root, "$RETIRED_FILE.part"))
    private fun memory() = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    private fun file(model: OfflineModel) = File(root, "${model.id}-${model.spec.sha256.take(12)}.${model.extension}")
    private fun partial(model: OfflineModel) = File(root, "${file(model).name}.part")
    private fun status() = OfflineModel.entries.associateWith {
        if (ready(it)) ModelState(it.spec.bytes, true) else ModelState(partial(it).length().coerceIn(0L, it.spec.bytes), false)
    }
}

/** Downloads only public model weights. A final app-private path is never visible until SHA-256 passes. */
internal fun downloadModel(
    client: OkHttpClient, spec: PinnedModel, partial: File, destination: File,
    signal: CancellationSignal, progress: (Long) -> Unit
) {
    if (partial.length() > spec.bytes && !partial.delete()) throw IOException("Cannot reset model download")
    var offset = partial.length()
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    fun hashPartial() = partial.inputStream().use { input ->
        while (true) {
            signal.throwIfCanceled()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    progress(offset)
    if (offset > 0) hashPartial()
    if (offset < spec.bytes) {
        val request = Request.Builder().url(spec.url).header("Accept-Encoding", "identity")
        if (offset > 0) request.header("Range", "bytes=$offset-")
        val call = client.newCall(request.build())
        signal.setOnCancelListener(call::cancel)
        try {
            call.execute().use { response ->
                val body = response.body ?: throw IOException("Empty model download")
                if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) {
                    throw IOException("Unexpected model content encoding")
                }
                when (response.code) {
                    200 -> { offset = 0; digest.reset() } // The server ignored Range; restart safely.
                    206 -> if (response.header("Content-Range") != "bytes $offset-${spec.bytes - 1}/${spec.bytes}") {
                        throw IOException("Unexpected model byte range")
                    }
                    else -> throw IOException("Model host returned HTTP ${response.code}")
                }
                if (body.contentLength() >= 0 && body.contentLength() != spec.bytes - offset) {
                    throw IOException("Unexpected model size")
                }
                body.byteStream().use { input ->
                    FileOutputStream(partial, offset > 0).use { output ->
                        var lastUpdate = 0L
                        while (true) {
                            signal.throwIfCanceled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n > spec.bytes - offset) throw IOException("Oversized model download")
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            offset += n
                            val now = System.nanoTime()
                            if (now - lastUpdate >= 500_000_000L) { lastUpdate = now; progress(offset) }
                        }
                        if (offset == spec.bytes) output.fd.sync()
                    }
                }
            }
        } finally { signal.setOnCancelListener(null) }
    }
    signal.throwIfCanceled()
    if (offset != spec.bytes || partial.length() != spec.bytes) throw IOException("Incomplete model download")
    if (!digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }.equals(spec.sha256, ignoreCase = true)) {
        partial.delete()
        throw OfflineFailure(OfflineIssue.CHECKSUM)
    }
    if (!partial.renameTo(destination)) throw IOException("Cannot install verified model")
    progress(offset)
}
