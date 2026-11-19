package com.ahcrazy.video2.data.offline

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.PowerManager
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
internal data class ModelAsset(val name: String, val spec: PinnedModel, val backupName: String, val importName: String? = null)
internal const val MIB = 1024L * 1024
private const val HF = "https://huggingface.co"
private const val WHISPER = "$HF/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"
private const val SILERO = "$HF/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v6.2.0.bin"
internal const val RETIRED_TRANSLATOR_ID = "hymt2-7b-q6"
private const val RETIRED_FILE = "$RETIRED_TRANSLATOR_ID-88ef0aba5995.gguf"
private const val HYMT_LITE_REV = "$HF/tencent/Hy-MT2-1.8B-GGUF/resolve/b27182d810fa3ceb6ed04e7c324c54e35c0d209c"
private const val HYMT = "$HF/tencent/Hy-MT2-7B-GGUF/resolve/ab8472660ac61fac25f1af43fac2599d52a8a775"

internal enum class OfflineModel(
    val id: String, val label: String, val assets: List<ModelAsset>,
    val minTotalRam: Long, val requiredFreeRam: Long, val minSdk: Int = 26
) {
    // Multilingual Small (not small.en). Same publisher revision and byte/digest verification as Medium.
    WHISPER_SMALL_Q5("whisper-small-q5", "Whisper Small · Q5_1",
        PinnedModel("$WHISPER/ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
        "bin", 3_072 * MIB, 1_100 * MIB),
    WHISPER_MEDIUM("whisper-medium", "Whisper Medium · F16",
        PinnedModel("$WHISPER/ggml-medium.bin", 1_533_763_059, "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208"),
        "bin", 5_120 * MIB, 2_400 * MIB),
    WHISPER_MEDIUM_Q5("whisper-medium-q5", "Whisper Medium · Q5_0",
        PinnedModel("$WHISPER/ggml-medium-q5_0.bin", 539_212_467, "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f"),
        "bin", 4_096 * MIB, 1_500 * MIB),
    WHISPER_TURBO("whisper-large-v3-turbo-q5", "Whisper large-v3 Turbo · Q5_0",
        PinnedModel("$WHISPER/ggml-large-v3-turbo-q5_0.bin", 574_041_195, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
        "bin", 5_120 * MIB, 2_100 * MIB),
    WHISPER_LARGE("whisper-large-v3", "Whisper large-v3 · F16",
        PinnedModel("$WHISPER/ggml-large-v3.bin", 3_095_033_483, "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2"),
        "bin", 7_168 * MIB, 4_300 * MIB),
    HYMT_LITE("hymt2-1p8b-q4", "Hy-MT2 1.8B · Q4_K_M",
        PinnedModel("$HYMT_LITE_REV/Hy-MT2-1.8B-Q4_K_M.gguf", 1_133_080_448, "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"),
        "gguf", 4_096 * MIB, 1_400 * MIB),
    HYMT_Q4("hymt2-7b-q4", "Hy-MT2 7B · Q4_K_M",
        PinnedModel("$HYMT/Hy-MT2-7B-Q4_K_M.gguf", 4_624_648_896, "9f96256500f3fc1ab4d64336b58f52a949a95ad7516b0c229476eef782f9f77b"),
        "gguf", 7_168 * MIB, 3_072 * MIB),
    MOONSHINE_EN(MOONSHINE_ID, "Moonshine Small · English", moonshineAssets, 3_072 * MIB, 800 * MIB),
    FIREFOX_EN_AR(FIREFOX_ID, "Firefox Translations · EN → AR", firefoxAssets, 3_072 * MIB, 600 * MIB, minSdk = 28),
    FIREFOX_ES_AR(FIREFOX_ES_ID, "Firefox Translations · ES → EN → AR", firefoxSpanishAssets, 3_072 * MIB, 800 * MIB, minSdk = 28),
    FIREFOX_IT_AR(FIREFOX_IT_ID, "Firefox Translations · IT → EN → AR", firefoxItalianAssets, 3_072 * MIB, 800 * MIB, minSdk = 28),
    SILERO_VAD("silero-vad-62", "Silero VAD · v6.2.0",
        PinnedModel(SILERO, 885_098, "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"),
        "bin", 3_072 * MIB, 256 * MIB);

    constructor(id: String, label: String, spec: PinnedModel, extension: String, minTotalRam: Long, requiredFreeRam: Long) :
        this(id, label, listOf(singleAsset(id, spec, extension)), minTotalRam, requiredFreeRam)

    val bytes = assets.sumOf { it.spec.bytes }
    val isBundle get() = assets.size > 1
    val spec get() = assets.single().spec // The historical one-file weight pins.
    val installedName get() = if (isBundle) id else assets.single().name
    val publisherName get() = assets.single().importName
    val isMoonshine get() = this == MOONSHINE_EN
    val firefoxSource: String? get() = when (this) {
        FIREFOX_EN_AR -> "en"
        FIREFOX_ES_AR -> "es"
        FIREFOX_IT_AR -> "it"
        else -> null
    }
    val isFirefox get() = firefoxSource != null
    val isFirefoxPivot get() = isFirefox && this != FIREFOX_EN_AR
    val requiredBundles get() = if (isFirefoxPivot) listOf(this, FIREFOX_EN_AR) else listOf(this)

    val memoryIssue: OfflineIssue get() = when (this) {
        WHISPER_SMALL_Q5, WHISPER_MEDIUM, WHISPER_MEDIUM_Q5, WHISPER_TURBO, MOONSHINE_EN, SILERO_VAD -> OfflineIssue.MEMORY
        WHISPER_LARGE -> OfflineIssue.LARGE_MODEL_MEMORY
        HYMT_LITE, HYMT_Q4, FIREFOX_EN_AR, FIREFOX_ES_AR, FIREFOX_IT_AR -> OfflineIssue.TRANSLATOR_MEMORY
    }

    val supportsFastSpeech get() = this in speechModels && this != WHISPER_LARGE && !isMoonshine
    val supportsStreamingSpeech get() = this == WHISPER_SMALL_Q5 || this == WHISPER_MEDIUM_Q5 || this == WHISPER_TURBO

    companion object {
        val speechModels = listOf(WHISPER_MEDIUM_Q5, WHISPER_TURBO, WHISPER_MEDIUM, WHISPER_LARGE, WHISPER_SMALL_Q5, MOONSHINE_EN)
        val translators = listOf(HYMT_LITE, HYMT_Q4, FIREFOX_EN_AR, FIREFOX_ES_AR, FIREFOX_IT_AR)
        fun translatorForJob(id: String?) = translators.firstOrNull { it.id == id }
        fun firefoxFor(source: String, target: String) = if (target == "ar" && source != "auto") {
            translators.firstOrNull { it.firefoxSource == source }
        } else null

        // Version 1.7 always used Large-v3, before jobs stored a speech model ID.
        fun speechForJob(id: String?): OfflineModel? = if (id == null) WHISPER_LARGE else speechModels.firstOrNull { it.id == id }

        fun pack(speech: OfflineModel, translator: OfflineModel, silenceGate: Boolean = false): List<OfflineModel> {
            require(speech in speechModels && translator in translators && (!silenceGate || !speech.isMoonshine))
            return listOf(speech) + translator.requiredBundles + listOfNotNull(SILERO_VAD.takeIf { silenceGate })
        }
    }
}

private fun singleAsset(id: String, spec: PinnedModel, extension: String): ModelAsset {
    val name = "$id-${spec.sha256.take(12)}.$extension"
    return ModelAsset(name, spec, name, spec.url.substringAfterLast('/'))
}

// Translation weights are file-backed mmap pages, not a 4.62 GB heap allocation.
// Preserve headroom for native scratch/KV and Android. Be more conservative below ~10 GiB physical RAM.
internal fun canOpenModel(model: OfflineModel, total: Long, available: Long, threshold: Long, low: Boolean): Boolean {
    val floor = if (model == OfflineModel.HYMT_Q4 && total < 9_728 * MIB) 4_096 * MIB else model.requiredFreeRam
    return !low && available >= maxOf(floor, threshold + 512 * MIB)
}

internal fun translationUnderPressure(available: Long, threshold: Long, low: Boolean): Boolean =
    low || available < threshold + 256 * MIB

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
    private val prefs = context.getSharedPreferences("video2-model-folder", Context.MODE_PRIVATE)
    private val _folder = MutableStateFlow(prefs.getString("uri", null)?.let(Uri::parse)?.takeIf(::isLocalModelFolder))
    val folder = _folder.asStateFlow()
    private val _transferCount = MutableStateFlow<Int?>(null)
    val transferCount = _transferCount.asStateFlow()

    // Prefer already-installed models to avoid unnecessary downloads.
    val defaultSpeech: OfflineModel
        get() = OfflineModel.speechModels.firstOrNull { !it.isMoonshine && ready(it) && compatible(it) } ?: OfflineModel.WHISPER_MEDIUM_Q5

    val defaultTranslator: OfflineModel get() = OfflineModel.translators.firstOrNull { !it.isFirefox && ready(it) && compatible(it) } ?: OfflineModel.HYMT_LITE

    val rapidWhisper: OfflineModel get() = chooseRapidWhisper { ready(it) && compatible(it) }
    val moonshineIssue: OfflineIssue? get() = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") bundledMoonshineIssue
        else OfflineIssue.NOT_SUPPORTED

    fun compatible(model: OfflineModel): Boolean = Build.VERSION.SDK_INT >= model.minSdk && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
        (!model.isMoonshine || moonshineIssue == null) && !manager.isLowRamDevice && memory().totalMem >= model.minTotalRam

    // Vulkan is optional; the driver and actual native backend are checked again on load.
    fun supportsVulkan() = Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x00402000) // Vulkan 1.2

    fun supportsOverlap(speech: OfflineModel, translator: OfflineModel) = supportsVulkan() &&
        translator == OfflineModel.HYMT_LITE && speech.supportsStreamingSpeech &&
        memory().totalMem >= 8_192 * MIB && !manager.isLowRamDevice

    private fun thermallyLimited() = Build.VERSION.SDK_INT >= 29 &&
        context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    fun canUseVulkan(model: OfflineModel): Boolean {
        val ram = memory()
        return !thermallyLimited() && !ram.lowMemory && ram.availMem >= maxOf(model.requiredFreeRam + 512 * MIB, ram.threshold + 768 * MIB)
    }

    fun overlapHasHeadroom(): Boolean {
        val ram = memory()
        return !thermallyLimited() && !ram.lowMemory && ram.availMem >= maxOf(2_048 * MIB, ram.threshold + 512 * MIB)
    }

    fun ready(model: OfflineModel) = model.assets.all { ready(model, it) }

    private fun ready(model: OfflineModel, asset: ModelAsset) = file(model, asset).let { it.isFile && it.length() == asset.spec.bytes }

    fun path(model: OfflineModel): File {
        if (!ready(model)) throw OfflineFailure(OfflineIssue.MISSING_MODELS)
        if (!compatible(model)) throw OfflineFailure(OfflineIssue.NOT_SUPPORTED)
        val ram = memory()
        if (!canOpenModel(model, ram.totalMem, ram.availMem, ram.threshold, ram.lowMemory)) throw OfflineFailure(model.memoryIssue)
        return File(root, model.installedName)
    }

    fun checkTranslationMemory() {
        val ram = memory()
        if (translationUnderPressure(ram.availMem, ram.threshold, ram.lowMemory)) throw OfflineFailure(OfflineIssue.TRANSLATOR_MEMORY)
    }

    fun downloadPack(speech: OfflineModel, translator: OfflineModel, silenceGate: Boolean = false) =
        download(OfflineModel.pack(speech, translator, silenceGate))

    fun download(model: OfflineModel) = download(model.requiredBundles)

    private fun download(pack: List<OfflineModel>) {
        if (work.status.value != null || pack.all(::ready)) return
        _issue.value = null
        _transferCount.value = null
        val backup = folder.value
        if (pack.any { !compatible(it) }) { _issue.value = OfflineIssue.NOT_SUPPORTED; return }
        val bytes = pack.sumOf { it.bytes }
        val total = bytes * (if (backup == null) 1 else 2)
        try {
            work.submit(OfflineWorkKind.DOWNLOAD, pack.joinToString("/") { it.id }, onFinish = { error ->
                _models.value = status()
                if (error != null && !OfflineWork.cancelled(error)) {
                    _issue.value = (error as? OfflineFailure)?.issue ?: OfflineIssue.DOWNLOAD
                }
            }) { signal, notify ->
                root.mkdirs()
                val needed = pack.sumOf { it.bytes - state(it).downloaded }
                if (needed > 0 && root.usableSpace < needed + 256 * MIB) throw OfflineFailure(OfflineIssue.STORAGE)
                var done = 0L
                for (model in pack) {
                    var inModel = 0L
                    for (asset in model.assets) {
                        signal.throwIfCanceled()
                        if (!ready(model, asset)) {
                            try {
                                val dest = file(model, asset)
                                dest.parentFile?.mkdirs()
                                downloadModel(http, asset.spec, partial(model, asset), dest, signal) { count ->
                                    // Include already-installed later assets (e.g. from a partial SAF restore).
                                    _models.value = _models.value + (model to state(model))
                                    notify((done + inModel + count).toDouble() / total)
                                }
                            } catch (e: IOException) {
                                if (root.usableSpace < 128 * MIB) throw OfflineFailure(OfflineIssue.STORAGE, e)
                                throw OfflineFailure(OfflineIssue.DOWNLOAD, e)
                            }
                        }
                        inModel += asset.spec.bytes
                        notify((done + inModel).toDouble() / total)
                    }
                    _models.value = status()
                    done += model.bytes
                }
                if (backup != null) {
                    val count = folderOperation(OfflineIssue.MODEL_BACKUP) {
                        backUp(pack, ModelFolder(context.contentResolver, backup), signal) { notify((bytes + it).toDouble() / total) }
                    }
                    _transferCount.value = count
                }
            }
        } catch (e: OfflineFailure) { _issue.value = e.issue }
    }

    fun chooseFolder(uri: Uri?) {
        if (work.status.value != null) return
        _issue.value = null
        _transferCount.value = null
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val old = folder.value
        if (uri != null) {
            if (!isLocalModelFolder(uri)) { _issue.value = OfflineIssue.MODEL_FOLDER; return }
            try { context.contentResolver.takePersistableUriPermission(uri, flags) }
            catch (_: SecurityException) { _issue.value = OfflineIssue.MODEL_FOLDER; return }
        }
        prefs.edit().putString("uri", uri?.toString()).apply()
        _folder.value = uri
        if (old != null && old != uri) runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
    }

    fun backUpInstalled() = transfer(OfflineWorkKind.MODEL_BACKUP, OfflineIssue.MODEL_BACKUP) { f, signal, notify ->
        val models = OfflineModel.entries.filter(::ready)
        val total = models.sumOf { it.bytes }.coerceAtLeast(1)
        backUp(models, f, signal) { notify(it.toDouble() / total) }
    }

    fun restoreModels() = transfer(OfflineWorkKind.MODEL_RESTORE, OfflineIssue.MODEL_RESTORE) { folder, signal, notify ->
        val documents = folder.documents()
        val candidates = OfflineModel.entries.associateWith { model ->
            model.assets.associateWith { folder.candidate(documents, it) }
        }
        // A backup may contain only part of a bundle. Resume with the files it actually contains,
        // but a bundle is not ready (nor counted as restored) until every verified asset is present.
        val copies = candidates.flatMap { (model, assets) -> assets.mapNotNull { (asset, doc) ->
            doc?.takeUnless { ready(model, asset) }?.let { Triple(model, asset, it) }
        } }
        if (copies.isEmpty()) {
            if (candidates.values.none { assets -> assets.values.any { it != null } }) throw OfflineFailure(OfflineIssue.EMPTY_MODEL_FOLDER)
            return@transfer 0
        }
        val total = copies.sumOf { (_, asset, _) -> asset.spec.bytes }
        copies.forEach { (model, asset, _) -> restoreStagingFile(file(model, asset)).delete() }
        var done = 0L
        var corrupt = false
        for ((model, asset, doc) in copies) {
            signal.throwIfCanceled()
            try {
                if (doc.size?.let { it != asset.spec.bytes } == true) throw OfflineFailure(OfflineIssue.CHECKSUM)
                if (root.usableSpace < asset.spec.bytes + 256 * MIB) throw OfflineFailure(OfflineIssue.STORAGE)
                val dest = file(model, asset)
                dest.parentFile?.mkdirs()
                restoreVerifiedWeight(asset.spec, dest, signal, { folder.open(doc) }) { count ->
                    notify((done + count).toDouble() / total)
                }
                partial(model, asset).delete()
                _models.value = status()
            } catch (error: OfflineFailure) {
                if (error.issue != OfflineIssue.CHECKSUM) throw error
                corrupt = true
            }
            done += asset.spec.bytes
            notify(done.toDouble() / total)
        }
        if (corrupt) _issue.value = OfflineIssue.MODEL_RESTORE_CHECKSUM
        copies.map { it.first }.distinct().count(::ready)
    }

    private fun backUp(models: List<OfflineModel>, folder: ModelFolder, signal: CancellationSignal, progress: (Long) -> Unit): Int {
        val documents = folder.documents()
        var done = 0L
        for (model in models) for (asset in model.assets) {
            signal.throwIfCanceled()
            folder.save(documents, asset, file(model, asset), signal) { count -> progress(done + count) }
            done += asset.spec.bytes
        }
        return models.size
    }

    private fun transfer(
        kind: OfflineWorkKind, failure: OfflineIssue,
        action: (ModelFolder, CancellationSignal, (Double) -> Unit) -> Int
    ) {
        if (work.status.value != null) return
        _issue.value = null
        _transferCount.value = null
        val uri = folder.value ?: return
        try {
            work.submit(kind, "model-folder", onFinish = { error ->
                _models.value = status()
                if (error != null && !OfflineWork.cancelled(error)) _issue.value = (error as? OfflineFailure)?.issue ?: failure
            }) { signal, notify ->
                _transferCount.value = folderOperation(failure) { action(ModelFolder(context.contentResolver, uri), signal, notify) }
            }
        } catch (e: OfflineFailure) { _issue.value = e.issue }
    }

    private inline fun <T> folderOperation(failure: OfflineIssue, action: () -> T): T = try { action() }
    catch (e: SecurityException) { throw OfflineFailure(OfflineIssue.MODEL_FOLDER, e) }
    catch (e: IOException) { throw OfflineFailure(failure, e) }
    catch (e: IllegalArgumentException) { throw OfflineFailure(OfflineIssue.MODEL_FOLDER, e) }

    fun remove(model: OfflineModel) {
        if (work.status.value != null) return
        _issue.value = null
        val files = model.assets.flatMap { asset ->
            listOf(file(model, asset), partial(model, asset), restoreStagingFile(file(model, asset)))
        }
        if (files.map { !it.exists() || it.delete() }.any { !it }) _issue.value = OfflineIssue.SAVE
        if (model.isBundle) File(root, model.installedName).delete()
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
    private fun file(model: OfflineModel, asset: ModelAsset) = if (model.isBundle) File(File(root, model.installedName), asset.name) else File(root, asset.name)
    private fun partial(model: OfflineModel, asset: ModelAsset) = file(model, asset).let { File(it.parentFile, "${it.name}.part") }
    private fun state(model: OfflineModel): ModelState {
        var all = true
        val bytes = model.assets.sumOf {
            if (ready(model, it)) it.spec.bytes else {
                all = false
                partial(model, it).length().coerceIn(0L, it.spec.bytes)
            }
        }
        return ModelState(bytes, all)
    }
    private fun status() = OfflineModel.entries.associateWith(::state)
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
