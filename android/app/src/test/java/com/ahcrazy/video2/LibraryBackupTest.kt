package com.ahcrazy.video2

import android.content.ContextWrapper
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.data.backup.LibraryArchive
import com.ahcrazy.video2.data.backup.LibraryArchiveException
import com.ahcrazy.video2.data.backup.LibraryArchiveIssue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryBackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val mediaBytes = "mock media contents that must never be re-encoded".encodeToByteArray()
    private val cue = SubCue(0, 1.25, 4.5, "It's raining.", "الدنيا بتمطر.")

    @Test fun restoredLibraryKeepsTheMediaAndAllSrtBytesWithoutOverwriting() {
        val from = LibraryStore(context())
        val video = addVideo(from)
        from.createFolder("دروس")
        from.setFolder(video, from.folders.value.single().id)
        val output = archive(from)
        val report = output.first
        assertEquals(1, report.items)
        assertEquals(1, report.captioned)
        val manifest = unzip(output.second).getValue("kadr-library.json").decodeToString()
        assertFalse(manifest.contains(video.localPath))
        assertFalse(manifest.contains("secret-url"))
        assertEquals("دروس", JSONObject(manifest).getJSONArray("items").getJSONObject(0).getString("folder"))

        val dest = context()
        val into = LibraryStore(dest)
        val previous = addVideo(into, video.id)
        val codec = codec(into)
        assertEquals(report, codec.verify(output.second.inputStream(), CancellationSignal()) {})
        assertEquals(listOf(previous), into.videos.value)
        assertEquals(report, codec.restore(output.second.inputStream(), CancellationSignal()) {})
        val reloaded = LibraryStore(dest)
        val restored = reloaded.videos.value.single { it.id != previous.id }
        assertEquals(previous, reloaded.byId(previous.id))
        assertEquals(video.title, restored.title)
        assertEquals("ar", restored.subtitleTargetLang)
        assertEquals(from.folders.value.single().name, reloaded.folders.value.single().name)
        assertEquals(reloaded.folders.value.single().id, restored.folderId)
        assertEquals("", restored.sourceUrl)
        assertNull(restored.pageUrl)
        assertNull(restored.dubbedAudioPath)
        assertArrayEquals(mediaBytes, File(restored.localPath).readBytes())
        assertEquals(video.subtitleFiles.keys, restored.subtitleFiles.keys)
        video.subtitleFiles.forEach { (key, value) ->
            assertArrayEquals(File(value).readBytes(), File(restored.subtitleFiles.getValue(key)).readBytes())
        }
        val translated = SrtCodec.parse(File(restored.subtitleFiles.getValue("target"))).single()
        assertEquals(cue.translated, translated.original)
        assertEquals(cue.startSec, translated.startSec, 0.0)
        assertEquals(cue.endSec, translated.endSec, 0.0)
        assertArrayEquals(mediaBytes, unzip(archive(reloaded, restored.id).second).getValue("items/1/media.mp4"))
        reloaded.delete(restored)
        assertFalse(File(restored.localPath).parentFile!!.exists())
        assertTrue(File(previous.localPath).isFile)
    }

    @Test fun savesOfflineHlsAsABundleButDoesNotPromiseToCopyRemotePlaylists() {
        val from = LibraryStore(context())
        val hls = addHls(from)
        val external = File(from.videosDir, "imported.m3u8").apply { writeText("#EXTM3U\nhttps://example.org/video.ts\n") }
        from.add(hls.copy(id = "external", title = "External stream", localPath = external.path))
        val (report, zip) = archive(from)
        assertEquals(listOf("External stream"), report.excluded)
        val to = LibraryStore(context())
        val other = addVideo(to)
        codec(to).restore(zip.inputStream(), CancellationSignal()) {}
        val restored = to.videos.value.single { it.kind == MediaKind.HLS }
        val bundle = File(restored.localPath).parentFile!!
        assertEquals("index.m3u8", File(restored.localPath).name)
        val original = File(hls.localPath).parentFile!!
        assertEquals(original.list()!!.toSet(), bundle.list()!!.toSet())
        for (name in original.list()!!) assertArrayEquals(File(original, name).readBytes(), File(bundle, name).readBytes())
        assertEquals(1, archive(to, restored.id).first.items)
        to.delete(restored)
        assertFalse(bundle.exists())
        assertTrue(File(other.localPath).isFile)
    }

    @Test fun invalidPayloadsNeverAppearInTheLibraryAndDoNotLeaveStagedCopies() {
        val from = LibraryStore(context())
        addVideo(from)
        val (_, zip) = archive(from)
        val entries = unzip(zip)
        val to = LibraryStore(context())
        val previous = addVideo(to, "kept")
        val staging = File(to.videosDir.parentFile!!, "pending-restore")
        val codec = LibraryArchive(to, staging)
        val missingEnd = rezipped(entries - "kadr-complete")
        val wrongName = rezipped(entries.entries.associate { (name, bytes) ->
            (if (name == "items/1/media.mp4") "../../escaped.mp4" else name) to bytes
        })
        val badCrc = rezipped(entries, stored = true).also { stored ->
            val offset = stored.indices.first { i -> i + mediaBytes.size <= stored.size &&
                mediaBytes.indices.all { n -> stored[i + n] == mediaBytes[n] } }
            stored[offset] = 'X'.code.toByte()
        }
        val tooLong = rezipped(entries.mapValues { (name, bytes) ->
            if (name == "items/1/media.mp4") bytes + byteArrayOf(1, 2, 3) else bytes
        })
        for (broken in listOf(missingEnd, wrongName, badCrc, tooLong)) {
            val error = assertThrows(LibraryArchiveException::class.java) {
                codec.restore(broken.inputStream(), CancellationSignal()) {}
            }
            assertEquals(LibraryArchiveIssue.INVALID, error.issue)
            assertEquals(listOf(previous), to.videos.value)
            assertFalse(staging.exists())
            assertEquals(listOf(File(previous.localPath).name), to.videosDir.list()!!.toList())
        }
        val signal = CancellationSignal()
        assertThrows(OperationCanceledException::class.java) {
            codec.restore(zip.inputStream(), signal) { signal.cancel() }
        }
        assertEquals(listOf(previous), to.videos.value)
        assertFalse(staging.exists())
    }

    @Test fun remoteOrOutOfBundleHlsReferencesAreRejectedEvenWithAValidZip() {
        val from = LibraryStore(context())
        addHls(from)
        val (report, archive) = archive(from)
        assertEquals(1, report.items)
        val entries = unzip(archive)
        val name = "items/1/video.m3u8"
        val original = entries.getValue(name).decodeToString()
        val to = LibraryStore(context())
        // Both replacements are exactly the same length as video-seg-0.ts; length/CRC alone pass.
        for (reference in listOf("//a.b/play1.ts", "../secret00.ts")) {
            assertEquals("video-seg-0.ts".length, reference.length)
            val modified = rezipped(entries.mapValues { (key, bytes) ->
                if (key == name) original.replace("video-seg-0.ts", reference).encodeToByteArray() else bytes
            })
            val err = assertThrows(LibraryArchiveException::class.java) { codec(to).verify(modified.inputStream(), CancellationSignal()) {} }
            assertEquals(LibraryArchiveIssue.INVALID, err.issue)
        }
        assertTrue(to.videos.value.isEmpty())
    }

    @Test fun missingAttachedCaptionsAreNotSilentlyDroppedFromTheBackup() {
        val library = LibraryStore(context())
        val video = addVideo(library)
        File(video.subtitleFiles.getValue("target")).delete()
        val output = ByteArrayOutputStream()
        val err = assertThrows(LibraryArchiveException::class.java) { codec(library).save(output, null, CancellationSignal()) {} }
        assertEquals(LibraryArchiveIssue.UNAVAILABLE, err.issue)
        assertEquals(0, output.size())
        assertTrue(File(video.localPath).isFile)
    }

    private fun context(): ContextWrapper {
        val dir = tmp.newFolder()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = dir }
    }
    private fun codec(library: LibraryStore) = LibraryArchive(library, File(library.videosDir.parentFile!!, "pending-restore"))
    private fun archive(library: LibraryStore, id: String? = null) = ByteArrayOutputStream().let { bytes ->
        codec(library).save(bytes, id, CancellationSignal()) {} to bytes.toByteArray()
    }
    private fun addVideo(store: LibraryStore, id: String = "lesson"): SavedVideo {
        val media = File(store.videosDir, "$id.mp4").apply { writeBytes(mediaBytes) }
        val subtitles = mapOf("orig" to "original", "target" to "translated", "bilingual" to "bilingual").mapValues { (key, mode) ->
            File(store.subtitlesDir, "$id-$key.srt").apply { writeText(SrtCodec.write(listOf(cue), mode).replace("\n", "\r\n")) }.path
        }
        return SavedVideo(id = id, title = "درس رقم ١", sourceUrl = "https://site.example/secret-url", localPath = media.path,
            pageUrl = "https://site.example/secret-url-page", subtitleFiles = subtitles, subtitleTargetLang = "ar", durationSec = 60.0)
            .also(store::add)
    }
    private fun addHls(store: LibraryStore): SavedVideo {
        val dir = File(store.videosDir, "bundle").apply { mkdir() }
        val master = File(dir, "index.m3u8").apply {
            writeText("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"offline-audio\",URI=\"audio.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO=\"offline-audio\"\nvideo.m3u8\n")
        }
        for (track in listOf("video", "audio")) {
            File(dir, "$track.m3u8").writeText("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:4\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"$track-key-0.bin\"\n#EXTINF:4.0,\n$track-seg-0.ts\n#EXT-X-ENDLIST\n")
            File(dir, "$track-key-0.bin").writeBytes(ByteArray(16) { it.toByte() })
            File(dir, "$track-seg-0.ts").writeBytes(mediaBytes)
        }
        return SavedVideo(id = dir.name, title = "HLS", sourceUrl = "", localPath = master.path, kind = MediaKind.HLS).also(store::add)
    }
    private fun unzip(bytes: ByteArray) = ZipInputStream(bytes.inputStream()).use { zip ->
        buildMap { while (true) { val part = zip.nextEntry ?: break; put(part.name, zip.readBytes()) } }
    }
    private fun rezipped(entries: Map<String, ByteArray>, stored: Boolean = false) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply {
                    if (stored) {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    }
                })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
