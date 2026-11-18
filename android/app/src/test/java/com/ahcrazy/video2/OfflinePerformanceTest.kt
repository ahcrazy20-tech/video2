package com.ahcrazy.video2

import android.app.Application
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.ahcrazy.video2.core.model.GpuFallback
import com.ahcrazy.video2.core.model.OfflinePreset
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.core.model.TranslationPhase
import com.ahcrazy.video2.data.offline.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflinePerformanceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun await(latch: CountDownLatch) = assertTrue("worker did not reach its boundary", latch.await(8, TimeUnit.SECONDS))
    private fun pcm(seconds: Long, file: File) = RandomAccessFile(file, "rw").use { audio ->
        audio.setLength(seconds * SAMPLE_RATE * 2)
        for (s in 0L until seconds step 50) {
            audio.seek(s * SAMPLE_RATE * 2)
            audio.writeShort(500)
        }
    }

    private fun segment(start: Long, text: String) = SpeechSegment(start, start + 90, text)
    private fun dense(count: Int) = (1..count).map { segment(it * 150L, "Caption $it") }
    private fun fakeTranslation(prompt: String) = prompt.substringAfter("[Source Text]\n")
        .split(SUBTITLE_DELIMITER).joinToString(SUBTITLE_DELIMITER) { "ترجمة ${it.trim()}" }

    @Test fun gpuDecodeFailureRetriesOnlyTheUncommittedWindowAndRecordsBothBackends() {
        val input = tmp.newFile().apply { writeText("video") }
        val dir = tmp.newFolder()
        val calls = mutableListOf<Pair<Boolean, Int>>()
        var gpuClosed = false
        var cpuClosed = false
        var clock = 0L
        val stats = OfflineStats { clock }
        fun speech(gpu: Boolean) = object : LocalSpeech {
            override val vulkan = gpu
            override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult {
                calls += gpu to pcm.size / SAMPLE_RATE
                clock += if (gpu) 30 else 70
                if (gpu && calls.size == 2) error("driver returned an inference error")
                return SpeechResult("en", if (gpu) listOf(segment(100, "First")) else listOf(segment(700, "Second")))
            }
            override fun close() { if (gpu) gpuClosed = true else cpuClosed = true }
        }
        val pipeline = OfflinePipeline({ input }, object : LocalEngines {
            override fun speechVulkan(model: File, signal: CancellationSignal): LocalSpeech = speech(true)
            override fun speech(model: File, signal: CancellationSignal): LocalSpeech {
                assertTrue(gpuClosed)
                return speech(false)
            }
            override fun translator(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator {
                assertTrue(cpuClosed)
                clock += 40
                return object : LocalTranslator {
                    override fun translate(prompt: String, seed: Int): String { clock += 90; return fakeTranslation(prompt) }
                    override fun close() {}
                }
            }
        }, vulkanSupported = { true }, vulkanHeadroom = { true }) { _, audio, _, _ ->
            clock += 10
            pcm(55L, audio)
        }
        val cues = pipeline.run(dir, input, SubLang.AUTO, SubLang.AR, OfflineModel.WHISPER_MEDIUM_Q5, CancellationSignal(),
            preset = OfflinePreset.VULKAN, stats = stats) { _, _, _, _ -> }
        assertEquals(listOf(true to 54, true to 9, false to 9), calls)
        assertEquals(listOf(1.0, 53.0), cues.map { it.startSec })
        assertEquals(listOf("ترجمة First", "ترجمة Second"), cues.map { it.translated })
        with(stats.report()) {
            assertEquals(GpuFallback.ENGINE, gpuFallback)
            assertEquals(1, gpuWindows)
            assertEquals(1, cpuWindows)
            assertEquals(60L, speechGpuMs) // includes the failed call, not a successful GPU window
            assertEquals(70L, speechCpuMs)
            assertEquals(220L, translateMs)
            assertEquals(10L, extractMs)
            assertEquals(270L, firstBatchMs)
            assertEquals(360L, elapsedMs)
            assertFalse(overlapped)
        }
        val resumed = OfflineStats { clock }
        CueJson.write(OfflinePipeline.translatedCuesFile(dir, "ar", OfflineModel.HYMT_Q4), cues)
        assertEquals(cues, pipeline.run(dir, input, SubLang.AUTO, SubLang.AR, OfflineModel.WHISPER_MEDIUM_Q5, CancellationSignal(),
            preset = OfflinePreset.VULKAN, stats = resumed) { _, _, _, _ -> })
        with(resumed.report()) {
            assertTrue(reusedTranscript)
            assertEquals(2, reusedBatches)
            assertEquals(0, generationCalls)
            assertNull(firstBatchMs)
            assertNull(gpuFallback)
        }
    }

    @Test fun interruptedGpuAttemptResumesOnCpuWithoutRepeatingSavedAudioWindows() {
        val input = tmp.newFile().apply { writeText("video") }
        val dir = tmp.newFolder()
        val initial = CancellationSignal()
        val loads = mutableListOf<Boolean>()
        var windows = 0
        fun speech(gpu: Boolean, signal: CancellationSignal) = object : LocalSpeech {
            override val vulkan = gpu
            override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult {
                windows++
                if (windows == 2) { initial.cancel(); signal.throwIfCanceled() }
                return SpeechResult("en", if (windows == 1) listOf(segment(100, "First")) else listOf(segment(700, "Second")))
            }
            override fun close() {}
        }
        val pipeline = OfflinePipeline({ input }, object : LocalEngines {
            override fun speechVulkan(model: File, signal: CancellationSignal): LocalSpeech { loads += true; return speech(true, signal) }
            override fun speech(model: File, signal: CancellationSignal): LocalSpeech { loads += false; return speech(false, signal) }
            override fun translator(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator = error("same language")
        }, vulkanSupported = { true }, vulkanHeadroom = { true }) { _, audio, _, _ -> pcm(55L, audio) }
        fun run(signal: CancellationSignal, stats: OfflineStats = OfflineStats()) = pipeline.run(dir, input, SubLang.EN, SubLang.EN,
            OfflineModel.WHISPER_MEDIUM_Q5, signal, preset = OfflinePreset.VULKAN, stats = stats) { _, _, _, _ -> }
        assertThrows(OperationCanceledException::class.java) { run(initial) }
        // Simulate the marker left by a process/driver termination (graceful cancel removes it).
        File(dir, "offline-vulkan-active").writeText("vulkan")
        val stats = OfflineStats()
        val cues = run(CancellationSignal(), stats)
        assertEquals(listOf(true, false), loads)
        assertEquals(3, windows)
        assertEquals(listOf("First", "Second"), cues.map { it.translated })
        with(stats.report()) {
            assertTrue(resumed)
            assertTrue(sameLanguage)
            assertEquals(1, reusedSpeechWindows)
            assertEquals(1, cpuWindows)
            assertEquals(0, gpuWindows)
            assertEquals(GpuFallback.INTERRUPTED, gpuFallback)
            assertNull(firstBatchMs)
        }
        assertFalse(File(dir, "offline-vulkan-active").exists())
    }

    @Test fun overlapProducesSameBatchesContextAndOrderAndReturnsCpuBudgetWithoutReload() {
        val input = tmp.newFile().apply { writeText("video") }
        data class Run(val cues: List<SubCue>, val prompts: List<Pair<String, Int>>)
        fun run(preset: OfflinePreset): Run {
            val firstPrompt = CountDownLatch(1)
            var speechClosed = false
            var mtLoads = 0
            var mtClosed = false
            val prompts = mutableListOf<Pair<String, Int>>()
            val budgets = mutableListOf<Boolean>()
            val stats = OfflineStats()
            fun speech(gpu: Boolean) = object : LocalSpeech {
                private var window = 0
                override val vulkan = gpu
                override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult =
                    SpeechResult("en", when (window++) {
                        // 6 stable lines and exactly two following cues; the last batch must wait.
                        0 -> dense(8)
                        1 -> { if (preset == OfflinePreset.OVERLAP) await(firstPrompt); listOf(segment(700, "Tail")) }
                        else -> error("unexpected window count")
                    })
                override fun close() { speechClosed = true }
            }
            val loader = object : LocalEngines {
                override fun speech(model: File, signal: CancellationSignal): LocalSpeech = speech(false)
                override fun speechVulkan(model: File, signal: CancellationSignal): LocalSpeech = speech(true)
                override fun translatorForOverlap(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator {
                    assertFalse(speechClosed)
                    return mt()
                }
                override fun translator(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator {
                    assertTrue(speechClosed)
                    return mt()
                }
                private fun mt(): LocalTranslator {
                    mtLoads++
                    return object : LocalTranslator {
                        override fun setOverlapping(value: Boolean) { budgets += value }
                        override fun translate(prompt: String, seed: Int): String {
                            prompts += prompt to seed
                            firstPrompt.countDown()
                            return fakeTranslation(prompt)
                        }
                        override fun close() { mtClosed = true }
                    }
                }
            }
            val pipeline = OfflinePipeline({ input }, loader, vulkanSupported = { true }, vulkanHeadroom = { true },
                overlapSupported = { _, _ -> true }, overlapHeadroom = { true }) { _, audio, _, _ -> pcm(55L, audio) }
            val progress = mutableListOf<Double>()
            val cues = pipeline.run(tmp.newFolder(), input, SubLang.AUTO, SubLang.AR, OfflineModel.WHISPER_MEDIUM_Q5,
                CancellationSignal(), translator = OfflineModel.HYMT_LITE, preset = preset, stats = stats) { phase, p, _, _ ->
                if (phase == TranslationPhase.TRANSLATING) assertTrue(speechClosed)
                progress += p
            }
            assertEquals(1, mtLoads)
            assertTrue(speechClosed && mtClosed)
            assertEquals(progress.sorted(), progress)
            assertTrue(prompts.first().first.contains("Following lines: Caption 7 Caption 8"))
            with(stats.report()) {
                assertEquals(9, cues.size)
                assertEquals(3, generatedBatches)
                assertEquals(3, generationCalls)
                assertEquals(0, reusedBatches)
                assertNotNull(firstBatchMs)
                assertEquals(preset == OfflinePreset.OVERLAP, overlapped)
                assertFalse(overlapDeferred)
                assertNull(gpuFallback)
            }
            assertEquals(if (preset == OfflinePreset.OVERLAP) listOf(true, false, false) else listOf(false, false, false), budgets)
            return Run(cues, prompts)
        }
        val baseline = run(OfflinePreset.CPU)
        assertEquals(baseline, run(OfflinePreset.VULKAN))
        assertEquals(baseline, run(OfflinePreset.OVERLAP))
    }

    @Test fun memoryPressureDefersOnlyUnfinishedMtBatchesUntilWhisperReleases() {
        val input = tmp.newFile().apply { writeText("video") }
        var speechClosed = false
        val earlyClosed = CountDownLatch(1)
        val stats = OfflineStats()
        val prompts = mutableListOf<String>()
        val headroom = java.util.concurrent.atomic.AtomicBoolean(true)
        val pipeline = OfflinePipeline({ input }, object : LocalEngines {
            override fun speech(model: File, signal: CancellationSignal): LocalSpeech = error("CPU fallback not requested")
            override fun speechVulkan(model: File, signal: CancellationSignal) = object : LocalSpeech {
                override val vulkan = true
                private var first = true
                override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult {
                    if (first) { first = false; return SpeechResult("en", dense(14)) }
                    await(earlyClosed)
                    return SpeechResult("en", listOf(segment(700, "Tail")))
                }
                override fun close() { speechClosed = true }
            }
            private fun mt(early: Boolean) = object : LocalTranslator {
                override fun translate(prompt: String, seed: Int): String {
                    prompts += prompt.substringAfter("[Source Text]\n")
                    if (early) headroom.set(false)
                    return fakeTranslation(prompt)
                }
                override fun close() { if (early) earlyClosed.countDown() }
            }
            override fun translatorForOverlap(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator = mt(true)
            override fun translator(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator {
                assertTrue(speechClosed)
                assertEquals(0L, earlyClosed.count)
                return mt(false)
            }
        }, vulkanSupported = { true }, vulkanHeadroom = { true }, overlapSupported = { _, _ -> true },
            overlapHeadroom = headroom::get) { _, audio, _, _ -> pcm(55L, audio) }
        val cues = pipeline.run(tmp.newFolder(), input, SubLang.EN, SubLang.AR, OfflineModel.WHISPER_MEDIUM_Q5,
            CancellationSignal(), translator = OfflineModel.HYMT_LITE, preset = OfflinePreset.OVERLAP, stats = stats) { _, _, _, _ -> }
        assertEquals(15, cues.size)
        assertEquals(4, prompts.size)
        assertEquals(prompts.distinct(), prompts)
        with(stats.report()) {
            assertTrue(overlapDeferred)
            assertTrue(overlapped)
            assertEquals(4, generatedBatches)
            assertEquals(0, reusedBatches) // same-attempt recovery is not previous work
        }
    }

    @Test fun cancellingParentSignalsBothNativeSessionsAndJoinsBeforeReturn() {
        val input = tmp.newFile().apply { writeText("video") }
        val dir = tmp.newFolder()
        val parent = CancellationSignal()
        val mtRunning = CountDownLatch(1)
        val mtCancelled = CountDownLatch(1)
        var sttCancelled = false
        var mtClosed = false
        var sttClosed = false
        val pipeline = OfflinePipeline({ input }, object : LocalEngines {
            override fun speech(model: File, signal: CancellationSignal): LocalSpeech = error("CPU fallback not requested")
            override fun speechVulkan(model: File, signal: CancellationSignal): LocalSpeech {
                signal.setOnCancelListener { sttCancelled = true }
                return object : LocalSpeech {
                    override val vulkan = true
                    private var first = true
                    override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult {
                        if (first) { first = false; return SpeechResult("en", dense(8)) }
                        await(mtRunning)
                        parent.cancel()
                        signal.throwIfCanceled()
                        error("speech session didn't receive cancel")
                    }
                    override fun close() { signal.setOnCancelListener(null); sttClosed = true }
                }
            }
            override fun translator(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator {
                signal.setOnCancelListener { mtCancelled.countDown() }
                return object : LocalTranslator {
                    override fun translate(prompt: String, seed: Int): String {
                        mtRunning.countDown()
                        await(mtCancelled)
                        signal.throwIfCanceled()
                        error("MT session didn't receive cancel")
                    }
                    override fun close() { signal.setOnCancelListener(null); mtClosed = true }
                }
            }
        }, vulkanSupported = { true }, vulkanHeadroom = { true }, overlapSupported = { _, _ -> true },
            overlapHeadroom = { true }) { _, audio, _, _ -> pcm(55L, audio) }
        assertThrows(OperationCanceledException::class.java) {
            pipeline.run(dir, input, SubLang.EN, SubLang.AR, OfflineModel.WHISPER_MEDIUM_Q5, parent,
                translator = OfflineModel.HYMT_LITE, preset = OfflinePreset.OVERLAP) { _, _, _, _ -> }
        }
        assertTrue(sttCancelled && sttClosed && mtClosed)
        assertTrue(File(dir, "offline-stt/0.json").exists())
        assertFalse(File(dir, "offline-stt/1.json").exists())
        assertFalse(File(dir, "cues-original.json").exists())
        assertFalse(File(OfflinePipeline.translationCheckpoint(dir, SubLang.AR, OfflineModel.HYMT_LITE), "0.json").exists())
    }

    @Test fun reportRoundTripsAndUnknownSettingsDefaultToCpu() {
        var clock = 500L
        val stats = OfflineStats { clock }
        stats.gpuFallback = GpuFallback.HEADROOM
        stats.reusedSpeechWindows = 2
        stats.batch(0, fresh = false)
        stats.measure(OfflineStats.Stage.TRANSLATE) { clock += 2_000 }
        stats.batch(1, fresh = true)
        stats.measure(OfflineStats.Stage.EXPORT) { clock += 150 }
        val report = stats.report()
        assertEquals(2_000L, report.firstBatchMs)
        assertEquals(2_150L, report.elapsedMs)
        assertEquals(report, readOfflinePerformance(JSONObject(report.toJson().toString())))
        assertNull(readOfflinePerformance(JSONObject())?.firstBatchMs)
        assertEquals(OfflinePreset.CPU, OfflinePreset.fromStored("unknown"))
        assertEquals(OfflinePreset.OVERLAP, OfflinePreset.fromStored("OVERLAP"))
    }
}
