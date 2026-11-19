package com.ahcrazy.video2.data.backup

import android.os.CancellationSignal
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.data.LibraryStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal enum class LibraryArchiveIssue { EMPTY, UNAVAILABLE, INVALID, STORAGE, IO }
internal class LibraryArchiveException(val issue: LibraryArchiveIssue) : IOException(issue.name)
internal data class LibraryArchiveReport(val items: Int, val captioned: Int, val excluded: List<String> = emptyList())
internal fun checkFormat(ok: Boolean) { if (!ok) throw LibraryArchiveException(LibraryArchiveIssue.INVALID) }

private const val FORMAT = "kadr-library"
private const val MANIFEST = "kadr-library.json"
private const val COMPLETE = "kadr-complete"
private val completion = "Kadr library archive v1\n".encodeToByteArray()
private const val MAX_ITEMS = 2_000
private const val MAX_ENTRIES = 80_000
private const val MAX_MANIFEST = 8 * 1024 * 1024
private const val MAX_PLAYLIST = 4L * 1024 * 1024
private const val MAX_CAPTIONS = 16L * 1024 * 1024
private const val MAX_ARCHIVE = 1L shl 40
private const val STORAGE_MARGIN = 16L * 1024 * 1024
private val captions = mapOf("orig" to "original.srt", "target" to "translated.srt", "bilingual" to "bilingual.srt")
private val langTag = Regex("[a-zA-Z]{2,3}(?:[-_][a-zA-Z0-9]{2,8}){0,3}")

private data class Part(val name: String, val bytes: Long)
private data class Entry(val title: String, val kind: MediaKind, val created: Long, val duration: Double?,
                         val folder: String?, val language: String?, val parts: List<Part>) {
    val primary get() = if (kind == MediaKind.HLS) "index.m3u8" else "media.${kind.ext}"
    val subtitleParts get() = parts.filter { it.name in captions.values }
    val hasCaptions get() = subtitleParts.isNotEmpty()
    val mediaBytes get() = parts.filterNot { it.name in captions.values }.sumOf { it.bytes }
}
private data class Source(val file: File, val bytes: Long, val modified: Long = file.lastModified()) {
    fun checkUnchanged() {
        if (!file.isFile || file.length() != bytes || file.lastModified() != modified) {
            throw LibraryArchiveException(LibraryArchiveIssue.UNAVAILABLE)
        }
    }
}
private data class ExportItem(val entry: Entry, val sources: List<Source>)

/** Ordered stream: manifest, exact whitelisted payloads, completion record. No source paths or URLs are exported. */
internal class LibraryArchive(private val library: LibraryStore, private val staging: File) {
    fun save(output: OutputStream, videoId: String?, signal: CancellationSignal, progress: (Double) -> Unit): LibraryArchiveReport {
        val (items, folders) = library.archiveSnapshot()
        val selected = items.filter { videoId == null || it.id == videoId }
        val excluded = mutableListOf<String>()
        val pack = selected.mapNotNull { video ->
            signal.throwIfCanceled()
            if (!canInclude(video)) { excluded += video.title; null }
            else prepare(video, folders.firstOrNull { it.id == video.folderId }?.name, signal)
        }
        if (pack.isEmpty()) throw LibraryArchiveException(if (excluded.isEmpty()) LibraryArchiveIssue.EMPTY else LibraryArchiveIssue.UNAVAILABLE)
        val entries = pack.map { it.entry }
        val total = validateInventory(entries)
        val manifest = encode(entries)
        checkFormat(manifest.size <= MAX_MANIFEST)
        val count = TransferCounter(total, progress)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION) // Video is already compressed; still use ZIP CRCs and ZIP64.
            zip.writeEntry(MANIFEST, manifest)
            val buffer = ByteArray(128 * 1024)
            pack.forEachIndexed { index, item ->
                item.entry.parts.zip(item.sources).forEach { (part, source) ->
                    signal.throwIfCanceled()
                    source.checkUnchanged()
                    zip.putNextEntry(ZipEntry(path(index, part)))
                    source.file.inputStream().use { transfer(it, zip, part.bytes, buffer, signal, count) }
                    source.checkUnchanged()
                    zip.closeEntry()
                }
            }
            signal.throwIfCanceled()
            zip.writeEntry(COMPLETE, completion)
        }
        return report(entries, excluded)
    }

    fun verify(input: InputStream, signal: CancellationSignal, progress: (Double) -> Unit): LibraryArchiveReport =
        report(read(input, signal, progress) { _, _ -> null })

    fun restore(input: InputStream, signal: CancellationSignal, progress: (Double) -> Unit): LibraryArchiveReport {
        // Only the library transfer slot uses this staging path. A killed process never publishes it.
        staging.deleteRecursively()
        val published = File(library.videosDir, "restore-${UUID.randomUUID()}")
        var moved = false
        var added = false
        try {
            if (!staging.mkdirs()) throw LibraryArchiveException(LibraryArchiveIssue.STORAGE)
            var ids = emptyList<String>()
            val items = read(input, signal, progress) { inventory, total ->
                if (staging.usableSpace < total + STORAGE_MARGIN) throw LibraryArchiveException(LibraryArchiveIssue.STORAGE)
                ids = inventory.map {
                    signal.throwIfCanceled()
                    UUID.randomUUID().toString().also { id ->
                        if (!File(staging, id).mkdir()) throw LibraryArchiveException(LibraryArchiveIssue.STORAGE)
                    }
                }
                val target: (Int) -> File = { index -> File(staging, ids[index]) }
                target
            }
            val restored = items.mapIndexed { index, item ->
                val base = File(published, ids[index])
                val files = captions.filterValues { name -> item.parts.any { it.name == name } }
                    .mapValues { (_, name) -> File(base, name).absolutePath }
                SavedVideo(id = ids[index], title = item.title, sourceUrl = "", localPath = File(base, item.primary).absolutePath,
                    kind = item.kind, createdAt = item.created, durationSec = item.duration, fileSize = item.mediaBytes,
                    extractionMethod = "library-restore", subtitleFiles = files, subtitleTargetLang = item.language) to item.folder
            }
            signal.throwIfCanceled()
            if (published.exists() || !staging.renameTo(published)) throw LibraryArchiveException(LibraryArchiveIssue.STORAGE)
            moved = true
            signal.throwIfCanceled()
            library.addRestored(restored)
            added = true
            return report(items)
        } finally {
            if (!added && moved) published.deleteRecursively()
            staging.deleteRecursively()
        }
    }

    private fun canInclude(video: SavedVideo): Boolean {
        val file = File(video.localPath)
        if (video.kind == MediaKind.DASH || (video.kind != MediaKind.HLS && file.extension.lowercase() in setOf("m3u8", "mpd"))) return false
        return video.kind != MediaKind.HLS || (file.name == "index.m3u8" && file.parentFile?.name == video.id &&
            (file.parentFile?.parentFile == library.videosDir || library.isRestoredItem(video)))
    }

    private fun prepare(video: SavedVideo, folder: String?, signal: CancellationSignal): ExportItem {
        val media = File(video.localPath).canonicalFile
        if (!media.isFile || !media.under(library.videosDir)) throw LibraryArchiveException(LibraryArchiveIssue.UNAVAILABLE)
        val sources = linkedMapOf<String, Source>()
        fun add(name: String, file: File) {
            val size = file.length()
            if (!file.isFile || size <= 0L) throw LibraryArchiveException(LibraryArchiveIssue.UNAVAILABLE)
            sources[name] = Source(file, size)
        }
        if (video.kind == MediaKind.HLS) {
            val bundle = media.parentFile ?: throw LibraryArchiveException(LibraryArchiveIssue.UNAVAILABLE)
            val files = bundle.listFiles() ?: throw LibraryArchiveException(LibraryArchiveIssue.UNAVAILABLE)
            val parts = files.filter { ArchivedHls.isAsset(it.name) }.sortedBy { it.name }
            val texts = mutableMapOf<String, String>()
            for (part in parts) {
                signal.throwIfCanceled()
                checkFormat(part.canonicalFile == part.absoluteFile)
                add(part.name, part)
                val source = sources.getValue(part.name)
                if (part.name in ArchivedHls.playlists) {
                    checkFormat(source.bytes <= MAX_PLAYLIST)
                    val bytes = part.inputStream().use { bounded(it, source.bytes.toInt(), signal) }
                    checkFormat(bytes.size.toLong() == source.bytes)
                    texts[part.name] = bytes.decodeToString()
                }
            }
            ArchivedHls.validate(sources.keys, texts)
        } else add("media.${video.kind.ext}", media)
        for ((key, path) in video.subtitleFiles) {
            val name = captions[key] ?: throw LibraryArchiveException(LibraryArchiveIssue.UNAVAILABLE)
            val file = File(path).canonicalFile
            if (!file.under(library.subtitlesDir) && !(library.isRestoredItem(video) && file.parentFile == media.parentFile && file.name == name)) {
                throw LibraryArchiveException(LibraryArchiveIssue.UNAVAILABLE)
            }
            add(name, file)
        }
        val entry = Entry(video.title.take(1024), video.kind, video.createdAt.coerceAtLeast(0),
            video.durationSec?.takeIf { it.isFinite() && it > 0 }, folder?.take(256), video.subtitleTargetLang?.takeIf(langTag::matches),
            sources.map { (name, source) -> Part(name, source.bytes) })
        return ExportItem(entry, sources.values.toList())
    }

    private fun read(input: InputStream, signal: CancellationSignal, progress: (Double) -> Unit,
                     destination: (List<Entry>, Long) -> ((Int) -> File)?): List<Entry> {
        try {
            return ZipInputStream(input.buffered()).use { zip ->
                signal.throwIfCanceled()
                zip.expect(MANIFEST)
                val entries = decode(bounded(zip, MAX_MANIFEST, signal))
                val total = validateInventory(entries)
                val folder = destination(entries, total)
                val count = TransferCounter(total, progress)
                val buffer = ByteArray(128 * 1024)
                entries.forEachIndexed { index, item ->
                    val target = folder?.invoke(index)
                    val texts = mutableMapOf<String, String>()
                    item.parts.forEach { part ->
                        signal.throwIfCanceled()
                        zip.expect(path(index, part))
                        val local = target?.let { File(it, part.name) } // validated name, never a ZIP path
                        if (item.kind == MediaKind.HLS && part.name in ArchivedHls.playlists) {
                            val bytes = ByteArrayOutputStream(part.bytes.toInt())
                            transfer(zip, bytes, part.bytes, buffer, signal, count)
                            val text = bytes.toByteArray()
                            texts[part.name] = text.decodeToString()
                            local?.writeBytes(text)
                        } else if (local != null) {
                            local.outputStream().use { transfer(zip, it, part.bytes, buffer, signal, count) }
                        } else transfer(zip, null, part.bytes, buffer, signal, count)
                    }
                    if (item.kind == MediaKind.HLS) ArchivedHls.validate(item.parts.map { it.name }.filter(ArchivedHls::isAsset).toSet(), texts)
                }
                signal.throwIfCanceled()
                zip.expect(COMPLETE)
                checkFormat(bounded(zip, completion.size, signal).contentEquals(completion))
                checkFormat(zip.nextEntry == null)
                entries
            }
        } catch (_: ZipException) { throw LibraryArchiveException(LibraryArchiveIssue.INVALID) }
        catch (_: JSONException) { throw LibraryArchiveException(LibraryArchiveIssue.INVALID) }
        catch (_: java.io.EOFException) { throw LibraryArchiveException(LibraryArchiveIssue.INVALID) }
    }
}

private fun File.under(root: File) = path.startsWith(root.canonicalPath + File.separator)
private fun path(index: Int, part: Part) = "items/${index + 1}/${part.name}"
private fun report(entries: List<Entry>, excluded: List<String> = emptyList()) = LibraryArchiveReport(entries.size, entries.count { it.hasCaptions }, excluded)

private fun validateInventory(entries: List<Entry>): Long {
    checkFormat(entries.size in 1..MAX_ITEMS && entries.sumOf { it.parts.size.toLong() } <= MAX_ENTRIES)
    var total = 0L
    for (item in entries) {
        checkFormat(item.title.length in 1..1024 && item.created >= 0 && (item.folder?.length ?: 0) <= 256 &&
            (item.language == null || langTag.matches(item.language)) && item.kind != MediaKind.DASH)
        val seen = mutableSetOf<String>()
        for (part in item.parts) {
            checkFormat(seen.add(part.name) && part.bytes in 1..(MAX_ARCHIVE - total))
            checkFormat(when {
                part.name in captions.values -> part.bytes <= MAX_CAPTIONS
                item.kind != MediaKind.HLS -> part.name == item.primary
                part.name in ArchivedHls.playlists -> part.bytes <= MAX_PLAYLIST
                ArchivedHls.isKey(part.name) -> part.bytes == 16L
                else -> ArchivedHls.isAsset(part.name)
            })
            total += part.bytes
        }
        checkFormat(item.primary in seen)
    }
    return total
}

private fun encode(entries: List<Entry>): ByteArray = JSONObject().put("format", FORMAT).put("version", 1)
    .put("items", JSONArray().apply {
        entries.forEach { item ->
            put(JSONObject().put("title", item.title).put("kind", item.kind.name).put("created", item.created)
                .put("duration", item.duration).put("folder", item.folder).put("language", item.language)
                .put("files", JSONArray().apply { item.parts.forEach { put(JSONObject().put("name", it.name).put("bytes", it.bytes)) } }))
        }
    }).toString().encodeToByteArray()

private fun decode(bytes: ByteArray): List<Entry> {
    val root = JSONObject(bytes.decodeToString())
    checkFormat(root.getString("format") == FORMAT && root.optInt("version", -1) == 1)
    val items = root.getJSONArray("items")
    checkFormat(items.length() in 1..MAX_ITEMS)
    var count = 0
    return List(items.length()) { index ->
        val item = items.getJSONObject(index)
        val files = item.getJSONArray("files")
        checkFormat(files.length() in 1..(MAX_ENTRIES - count))
        count += files.length()
        val kind = MediaKind.entries.firstOrNull { it.name == item.getString("kind") } ?: throw LibraryArchiveException(LibraryArchiveIssue.INVALID)
        val duration = if (item.isNull("duration")) null else item.getDouble("duration").also { checkFormat(it.isFinite() && it > 0) }
        Entry(item.getString("title"), kind, item.getLong("created"), duration,
            item.optString("folder").takeIf(String::isNotBlank), item.optString("language").takeIf(String::isNotBlank),
            List(files.length()) { part -> files.getJSONObject(part).let { Part(it.getString("name"), it.getLong("bytes")) } })
    }
}

private fun ZipOutputStream.writeEntry(name: String, payload: ByteArray) {
    putNextEntry(ZipEntry(name)); write(payload); closeEntry()
}
private fun ZipInputStream.expect(name: String) {
    val entry = nextEntry
    checkFormat(entry != null && !entry.isDirectory && entry.name == name)
}
private fun bounded(input: InputStream, limit: Int, signal: CancellationSignal): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 32 * 1024))
    val buffer = ByteArray(16 * 1024)
    while (true) {
        signal.throwIfCanceled()
        val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size() + 1))
        if (n < 0) return out.toByteArray()
        checkFormat(n > 0 && n <= limit - out.size())
        out.write(buffer, 0, n)
    }
}
private fun transfer(input: InputStream, output: OutputStream?, bytes: Long, buffer: ByteArray, signal: CancellationSignal, count: TransferCounter) {
    var left = bytes
    while (left > 0) {
        signal.throwIfCanceled()
        val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
        checkFormat(n > 0)
        output?.write(buffer, 0, n)
        left -= n
        count.wrote(n)
    }
    signal.throwIfCanceled()
    checkFormat(input.read() == -1) // Limits inflated bytes and forces ZIP CRC validation.
}
private class TransferCounter(private val total: Long, private val progress: (Double) -> Unit) {
    private var bytes = 0L
    private var reported = 0L
    fun wrote(n: Int) {
        bytes += n
        if (bytes - reported >= 2 * 1024 * 1024 || bytes == total) {
            reported = bytes
            progress(.98 * bytes / total)
        }
    }
}
