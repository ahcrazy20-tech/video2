package com.ahcrazy.video2

import android.content.ContextWrapper
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.TimedDubWav
import com.ahcrazy.video2.core.dubbing.readSpeechWav
import com.ahcrazy.video2.core.model.DubbedInfo
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.data.dubbing.loadDubSource
import com.ahcrazy.video2.data.dubbing.publishDub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DubbingPublishTest {
    @Test fun updatesOnlyDubFieldsOnTheLatestLibraryEntryAndPersistsWhatWasHeard() = fixture { ctx, library, video, previous ->
        runBlocking {
            val source = loadDubSource(video)
            val info = DubbedInfo("android-tts", "fixture-voice", "ar-EG", 0, 3.0)
            publishDub(library, video, source, info) { partial ->
                library.rename(video, "اسم جديد")
                library.setFolder(video, "folder-new")
                library.updatePosition(video, 1.4, null)
                RandomAccessFile(partial, "rw").use { file ->
                    TimedDubWav(file, source.plan.frames).apply { add(24000, shortArrayOf(100)); finish() }
                }
            }
            val saved = requireNotNull(LibraryStore(ctx).byId(video.id))
            assertEquals("اسم جديد", saved.title)
            assertEquals("folder-new", saved.folderId)
            assertEquals(1.4, saved.lastPositionSec, 0.0)
            assertEquals(video.subtitleFiles, saved.subtitleFiles)
            assertEquals(video.localPath, saved.localPath)
            assertEquals("fixture-voice", saved.dubbedInfo?.voice)
            assertEquals("ar-EG", saved.dubbedInfo?.language)
            assertEquals(72_000, readSpeechWav(File(requireNotNull(saved.dubbedAudioPath))).samples.size)
            assertFalse(previous.exists())
        }
    }

    @Test fun failureAndCancellationAndStaleCaptionsKeepOldDubAndLeaveNoPartFile() = fixture { ctx, library, video, previous ->
        runBlocking {
            val source = loadDubSource(video)
            val info = DubbedInfo("android-tts", "fixture-voice", "ar-EG", 0, 3.0)
            val failed = runCatching {
                publishDub(library, video, source, info) { partial ->
                    partial.writeText("unfinished")
                    throw DubFailure(DubIssue.SYNTHESIS, 1)
                }
            }
            assertEquals(DubIssue.SYNTHESIS, (failed.exceptionOrNull() as? DubFailure)?.issue)
            val partialCreated = CompletableDeferred<Unit>()
            val job = launch {
                publishDub(library, video, source, info) { partial ->
                    partial.writeText("unfinished")
                    partialCreated.complete(Unit)
                    awaitCancellation()
                }
            }
            partialCreated.await()
            job.cancelAndJoin()
            val stale = runCatching {
                publishDub(library, video, source, info) { partial ->
                    partial.writeText("fixture")
                    library.update(requireNotNull(library.byId(video.id)).copy(subtitleTargetLang = "en"))
                }
            }
            assertEquals(DubIssue.SOURCE_CHANGED, (stale.exceptionOrNull() as? DubFailure)?.issue)
            assertEquals(previous.path, LibraryStore(ctx).byId(video.id)?.dubbedAudioPath)
            assertEquals("old", previous.readText())
            assertEquals(listOf(previous), previous.parentFile?.listFiles()?.toList())
        }
    }

    private fun fixture(block: (ContextWrapper, LibraryStore, SavedVideo, File) -> Unit) {
        val dir = Files.createTempDirectory("kadr-dub-publish").toFile()
        val ctx = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = dir }
        try {
            val library = LibraryStore(ctx)
            val previous = File(library.dubsDir, "fixture/dub.wav").apply { parentFile?.mkdirs(); writeText("old") }
            val srt = File(library.subtitlesDir, "translation.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n") }
            val video = SavedVideo(id = "fixture", title = "الفيديو", sourceUrl = "", localPath = File(dir, "fixture.mp4").path,
                durationSec = 3.0, subtitleFiles = mapOf("target" to srt.path), subtitleTargetLang = "ar", dubbedAudioPath = previous.path)
            library.add(video)
            block(ctx, library, video, previous)
        } finally { dir.deleteRecursively() }
    }
}
