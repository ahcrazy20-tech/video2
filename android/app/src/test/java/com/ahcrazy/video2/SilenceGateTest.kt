package com.ahcrazy.video2

import android.app.Application
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.data.offline.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SilenceGateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val whisper = OfflineModel.WHISPER_SMALL_Q5

    @Test fun onlyWhisperWithAnExplicitSourceCanOptIn() {
        assertTrue(supportsSilenceGate("es", whisper))
        assertTrue(supportsSilenceGate("it", whisper))
        assertFalse(supportsSilenceGate("auto", whisper))
        assertFalse(supportsSilenceGate("en", OfflineModel.MOONSHINE_EN))
        assertFalse(supportsSilenceGate("en", OfflineModel.SILERO_VAD))
        assertEquals(885_098L, OfflineModel.SILERO_VAD.bytes)
    }

    @Test fun compactedSpeechMapsToOriginalClockAndRejectsAmbiguousCues() {
        val pcm = ShortArray(50 * SAMPLE_RATE)
        val bounds = intArrayOf(600, 1200, 3500, 4100)
        pcm.fill(101, 6 * SAMPLE_RATE, 12 * SAMPLE_RATE)
        pcm.fill(202, 35 * SAMPLE_RATE, 41 * SAMPLE_RATE)
        val gate = checkNotNull(planSilenceGate(pcm, bounds))
        assertEquals(1_380 * 160, gate.samples.size)
        assertEquals(3_620 * 160, gate.removedFrames)
        assertEquals(101.toShort(), gate.samples[25 * 160])
        assertEquals(202.toShort(), gate.samples[755 * 160])
        val first = SpeechSegment(30, 623, "Habló Ana.")
        val second = SpeechSegment(765, 1325, "Eran las 17:30.")
        val result = gate.restore(SpeechResult("es", listOf(first, second)))!!
        assertEquals(listOf(first.copy(startCs = 605, endCs = 1198), second.copy(startCs = 3510, endCs = 4070)), result.segments)
        assertNull(gate.restore(SpeechResult("es", listOf(first)))) // An entire island would be lost.
        assertNull(gate.restore(SpeechResult("es", listOf(SpeechSegment(643, 751, "Joined across a pause")))))
        assertNull(gate.restore(SpeechResult("es", listOf(first, SpeechSegment(655, 685, "Synthetic audio"), second))))
        assertNull(planSilenceGate(pcm, intArrayOf(400, 4200, 4100, 4500)))
        assertNull(planSilenceGate(pcm, intArrayOf(4800, 5010)))
    }

    @Test fun energyVetoAndShortOrDenseWindowsRemainUnfiltered() {
        val noisy = ShortArray(50 * SAMPLE_RATE) { if (it % 2 == 0) 240 else -240 }
        assertNull(planSilenceGate(noisy, intArrayOf(600, 1200, 3500, 4100)))
        assertNull(planSilenceGate(noisy, intArrayOf())) // A detector miss is not silence.
        assertNull(planSilenceGate(noisy.copyOf(4 * SAMPLE_RATE), intArrayOf(100, 200)))
        assertNull(planSilenceGate(ShortArray(50 * SAMPLE_RATE), intArrayOf(50, 2300, 2350, 4980)))
        val almostSilent = ShortArray(50 * SAMPLE_RATE) { (it % 7).toShort() }
        assertEquals(almostSilent.size, checkNotNull(planSilenceGate(almostSilent, intArrayOf())).removedFrames)
    }

    @Test fun sameModelAndTimingWithAndWithoutGateAcrossTheWindowSeam() {
        val video = tmp.newFile().apply { writeText("same video") }
        val control = tmp.newFolder()
        val experiment = tmp.newFolder()
        val legacy = File(experiment, "offline-stt").apply { mkdirs() }
        File(legacy, "0.json").writeText("not a gate checkpoint")
        val loads = mutableListOf<OfflineModel>()
        val decoded = mutableListOf<Int>()
        val first = "Llegué a las 17:30."
        val seam = "Nos vemos mañana."
        val last = "Pero no sin Ana."
        val engine = object : LocalSpeech {
            override fun speechBounds(pcm: ShortArray, detector: File) = when (pcm.size / SAMPLE_RATE) {
                54 -> intArrayOf(600, 1200, 4900, 5020)
                34 -> intArrayOf(300, 420, 1900, 2400)
                else -> error("Unexpected window")
            }
            override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult {
                assertEquals("es", language)
                assertFalse(fast) // Same model, beam and device path for the comparison.
                val seconds = pcm.size / SAMPLE_RATE
                decoded += seconds
                return SpeechResult("es", when (seconds) {
                    54 -> listOf(SpeechSegment(620, 1170, first), SpeechSegment(4910, 5005, seam))
                    34 -> listOf(SpeechSegment(310, 405, seam), SpeechSegment(1920, 2380, last))
                    9 -> listOf(SpeechSegment(45, 595, first), SpeechSegment(765, 860, seam))
                    8 -> listOf(SpeechSegment(35, 130, seam), SpeechSegment(295, 755, last))
                    else -> error("Unexpected decode length: $seconds")
                })
            }
            override fun close() = Unit
        }
        val pipeline = pipeline(video, 80, engine) { loads += it; video }
        fun run(dir: File, gate: Boolean, stats: OfflineStats) = pipeline.run(dir, video, SubLang.ES, SubLang.ES, whisper,
            CancellationSignal(), stats = stats, silenceGate = gate) { _, _, _, _ -> }
        val baseline = run(control, false, OfflineStats())
        val stats = OfflineStats()
        val gated = run(experiment, true, stats)
        assertEquals(listOf(54, 34, 9, 8), decoded)
        assertEquals(baseline, gated)
        assertEquals(listOf(6.2, 49.1, 65.2), gated.map { it.startSec })
        assertEquals(listOf(first, seam, last), gated.map { it.original })
        assertEquals(listOf(whisper, whisper, OfflineModel.SILERO_VAD), loads)
        assertEquals(2, stats.report().vadWindows)
        assertEquals(71_000L, stats.report().vadSavedAudioMs) // Input windows include overlap.
        assertEquals(0, stats.report().reusedSpeechWindows)
        assertTrue(legacy.isDirectory)
    }

    @Test fun optionalDetectorFailureAndUnsafeJoinFallBackToTheSameFullDecode() {
        val video = tmp.newFile().apply { writeText("same video") }
        for (missingDetector in listOf(false, true)) {
            val calls = mutableListOf<Int>()
            val engine = object : LocalSpeech {
                override fun speechBounds(pcm: ShortArray, detector: File) = intArrayOf(600, 1200, 3500, 4100)
                override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult {
                    val seconds = pcm.size / SAMPLE_RATE
                    calls += seconds
                    return SpeechResult("es", if (seconds == 50) listOf(SpeechSegment(620, 1180, "Voy en camino."))
                        else listOf(SpeechSegment(25, 910, "Uncertain timing")))
                }
                override fun close() = Unit
            }
            val pipeline = pipeline(video, 50, engine) {
                if (missingDetector && it == OfflineModel.SILERO_VAD) throw OfflineFailure(OfflineIssue.MISSING_MODELS)
                video
            }
            val stats = OfflineStats()
            val output = pipeline.run(tmp.newFolder(), video, SubLang.ES, SubLang.ES, whisper, CancellationSignal(),
                stats = stats, silenceGate = true) { _, _, _, _ -> }
            assertEquals("Voy en camino.", output.single().original)
            assertEquals(6.2, output.single().startSec, 0.001)
            assertEquals(if (missingDetector) listOf(50) else listOf(13, 50), calls)
            assertEquals(missingDetector, stats.report().vadUnavailable)
            assertEquals(if (missingDetector) 0 else 1, stats.report().vadRetriedWindows)
            assertEquals(0L, stats.report().vadSavedAudioMs)
        }
    }

    @Test fun cancellationDuringDetectionStopsBeforeAnyDecodeOrCommit() {
        val signal = CancellationSignal()
        val video = tmp.newFile().apply { writeText("same video") }
        val dir = tmp.newFolder()
        val engine = object : LocalSpeech {
            override fun speechBounds(pcm: ShortArray, detector: File): IntArray {
                signal.cancel()
                return intArrayOf(600, 1200)
            }
            override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult =
                error("Cancelled window cannot run ASR")
            override fun close() = Unit
        }
        assertThrows(OperationCanceledException::class.java) {
            pipeline(video, 50, engine).run(dir, video, SubLang.IT, SubLang.IT, whisper, signal, silenceGate = true) { _, _, _, _ -> }
        }
        assertFalse(hasCheckpoint(File(dir, "cues-original.json")))
        assertTrue(File(dir, "offline-stt-gate-v1-${whisper.id}").listFiles().orEmpty().isEmpty())
    }

    private fun pipeline(video: File, length: Long, engine: LocalSpeech, modelPath: (OfflineModel) -> File = { video }) =
        OfflinePipeline(modelPath, object : LocalEngines {
            override fun speech(model: File, signal: CancellationSignal) = engine
            override fun translator(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator = error("No translation needed")
        }, pcmDecoder = { _, output, _, _ ->
            RandomAccessFile(output, "rw").use { pcm ->
                pcm.setLength(length * SAMPLE_RATE * 2)
                val islands = if (length == 80L) listOf(6L, 49L, 65L) else listOf(6L, 35L)
                for (second in islands) {
                    pcm.seek(second * SAMPLE_RATE * 2)
                    pcm.writeShort(500)
                }
            }
        })
}
