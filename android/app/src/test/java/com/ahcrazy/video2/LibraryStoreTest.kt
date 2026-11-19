package com.ahcrazy.video2

import android.content.ContextWrapper
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.ui.library.libraryResumeCandidate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryStoreTest {
    @Test fun oldLibraryKeepsPathsCaptionsAndResumeDataAfterSaving() {
        withFiles { ctx ->
            val legacy = File(ctx.filesDir, "library.json")
            legacy.writeText("""{"videos":[{"id":"kept","title":"ملف","sourceUrl":"https://example.org/a.mp4","localPath":"/original/a.mp4","kind":"MP4","durationSec":100,"lastPositionSec":20,"subtitleFiles":{"target":"/original/subs.srt"},"dubbedAudioPath":"/original/dub.m4a"}]}""")
            val library = LibraryStore(ctx)
            val original = requireNotNull(library.byId("kept"))
            assertEquals(0L, original.lastPlayedAt)
            assertEquals(20.0, original.lastPositionSec, 0.0)
            library.updatePosition(original, 25.0, null)
            val restored = requireNotNull(LibraryStore(ctx).byId("kept"))
            assertTrue(restored.lastPlayedAt > 0L)
            assertEquals(original.localPath, restored.localPath)
            assertEquals(original.subtitleFiles, restored.subtitleFiles)
            assertEquals(original.dubbedAudioPath, restored.dubbedAudioPath)
            assertEquals(25.0, restored.lastPositionSec, 0.0)
            assertTrue(JSONObject(legacy.readText()).has("videos"))
        }
    }

    @Test fun deletingAnImportedPlaylistCannotDeleteTheWholeVideoDirectory() {
        withFiles { ctx ->
            val store = LibraryStore(ctx)
            val other = File(store.videosDir, "kept.mp4").apply { writeText("fixture") }
            val playlist = File(store.videosDir, "imported.m3u8").apply { writeText("#EXTM3U\n") }
            val video = SavedVideo(id = "imported", title = "Imported playlist", localPath = playlist.path,
                sourceUrl = "", kind = MediaKind.HLS)
            store.add(video)
            store.delete(video)
            assertTrue(other.isFile)
            assertFalse(playlist.exists())
            assertNull(LibraryStore(ctx).byId(video.id))
        }
    }

    @Test fun resumeUsesRecentUnfinishedPlaybackWithALegacyFallback() {
        val old = SavedVideo(id = "old", title = "Old", sourceUrl = "", localPath = "/old.mp4", createdAt = 5L,
            durationSec = 100.0, lastPositionSec = 24.0)
        val recent = old.copy(id = "recent", lastPlayedAt = 30L)
        val finished = old.copy(id = "finished", lastPositionSec = 99.0, lastPlayedAt = 60L)
        assertEquals(old, libraryResumeCandidate(listOf(finished, old)))
        assertEquals(recent, libraryResumeCandidate(listOf(old, finished, recent)))
        assertNull(libraryResumeCandidate(listOf(finished, recent.copy(lastPositionSec = 2.0))))
    }

    private fun withFiles(block: (ContextWrapper) -> Unit) {
        val dir = Files.createTempDirectory("kadr-library-test").toFile()
        try {
            block(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir(): File = dir
            })
        } finally { dir.deleteRecursively() }
    }
}
