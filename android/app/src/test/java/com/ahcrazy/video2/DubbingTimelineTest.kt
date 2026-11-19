package com.ahcrazy.video2

import com.ahcrazy.video2.core.dubbing.DUB_SAMPLE_RATE
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.SpeechPcm
import com.ahcrazy.video2.core.dubbing.SpeechPcmHint
import com.ahcrazy.video2.core.dubbing.TimedDubWav
import com.ahcrazy.video2.core.dubbing.planDub
import com.ahcrazy.video2.core.dubbing.readSpeechWav
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.data.dubbing.fitSpeech
import com.ahcrazy.video2.data.dubbing.renderDub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin

class DubbingTimelineTest {
    @Test fun keepsEveryCueAtItsSrtTimeAndPadsGapsAndKnownVideoTail() = inDirectory { dir ->
        val cues = (0..306).map { i -> SubCue(i, 1.25 + i * .14, 1.35 + i * .14, "سطر $i") }
        val srt = SrtCodec.write(cues.reversed(), "original")
        val plan = planDub(SrtCodec.parse(srt), 45.0)
        val out = File(dir, "dub.wav")
        val scratch = File(dir, "cue.wav")
        val made = mutableListOf<String>()
        var progress = 0 to 0
        runBlocking {
            renderDub(plan, out, scratch, { line, file ->
                made += line.text
                writePcm16(file, shortArrayOf(11_000, 10_000, 9_000))
                SpeechPcmHint.HEADER
            }) { done, total -> progress = done to total }
        }
        assertEquals(cues.map { it.original }, made)
        assertEquals(307 to 307, progress)
        assertFalse(scratch.exists())
        val frames = 45 * DUB_SAMPLE_RATE
        val bytes = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", out.inputStream().use { it.readNBytes(4).toString(Charsets.US_ASCII) })
        assertEquals(36 + frames * 2, bytes.getInt(4))
        assertEquals(DUB_SAMPLE_RATE, bytes.getInt(24))
        assertEquals(frames * 2, bytes.getInt(40))
        assertEquals(44L + frames * 2L, out.length())
        val pcm = readSpeechWav(out).samples
        fun checkCue(at: Long) {
            assertEquals(0, pcm[at.toInt() - 1].toInt())
            assertEquals(11_000, pcm[at.toInt()].toInt())
            assertEquals(9_000, pcm[at.toInt() + 2].toInt())
            assertEquals(0, pcm[at.toInt() + 3].toInt())
        }
        assertEquals(0, pcm.first().toInt())
        assertEquals(1_250L * 24, plan.lines.first().start)
        assertEquals(44_090L * 24, plan.lines.last().start)
        plan.lines.forEach { checkCue(it.start) }
        assertEquals(0, pcm.last().toInt())
    }

    @Test fun overlappingSpeechIsMixedAtItsOwnStartAndSaturatesWithoutWrapping() = inDirectory { dir ->
        val out = File(dir, "overlap.wav")
        RandomAccessFile(out, "rw").use { file ->
            TimedDubWav(file, 10).apply {
                add(2, shortArrayOf(100, 20_000, -20_000, 120))
                add(3, shortArrayOf(20_000, -20_000, 1_000, 200))
                add(8, shortArrayOf(700))
                finish()
            }
        }
        assertArrayEquals(shortArrayOf(0, 0, 100, 32_767, -32_768, 1120, 200, 0, 700, 0), readSpeechWav(out).samples)
    }

    @Test fun parsesPaddedMetadataAndStereoWavAndRejectsTruncatedData() = inDirectory { dir ->
        val out = File(dir, "stereo.wav")
        val b = ByteBuffer.allocate(62).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(54).put("WAVEJUNK".toByteArray()).putInt(1).put(3.toByte()).put(0.toByte())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(16_000).putInt(64_000).putShort(4).putShort(16)
        b.put("data".toByteArray()).putInt(8).putShort(600).putShort(1400).putShort(-1100).putShort(100)
        out.writeBytes(b.array())
        val stereo = readSpeechWav(out)
        assertEquals(16_000, stereo.rate)
        assertArrayEquals(shortArrayOf(1000, -500), stereo.samples)
        RandomAccessFile(out, "rw").use { it.setLength(60) }
        assertEquals(DubIssue.AUDIO_FORMAT, assertThrows(DubFailure::class.java) { readSpeechWav(out) }.issue)
    }

    @Test fun readsAndroidFloatOutputEvenWhenTheWavHeaderSaysIntegerPcm() = inDirectory { dir ->
        val out = File(dir, "float.wav")
        val pcm = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(48).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(24_000).putInt(96_000).putShort(4).putShort(32)
            .put("data".toByteArray()).putInt(12).putFloat(.5f).putFloat(-1f).putFloat(Float.NaN)
        out.writeBytes(pcm.array())
        assertArrayEquals(shortArrayOf(16_384, -32_767, 0), readSpeechWav(out, SpeechPcmHint.FLOAT32).samples)
    }

    @Test fun resamplesAndSpeedsUpOnlyWhenNeededAndErrorsOnImpossibleCues() {
        fun tone(rate: Int, seconds: Double) = ShortArray((rate * seconds).toInt()) { i ->
            (12000 * sin(2 * PI * 200 * i / rate)).toInt().toShort()
        }
        val wav = SpeechPcm(16_000, tone(16_000, 1.0))
        val normal = fitSpeech(wav, 30_000, 1)
        assertTrue(normal.size in 23_950..24_050)
        val faster = fitSpeech(wav, 16_000, 1)
        assertTrue(faster.size in 15_000..16_000)
        val crossings = faster.indices.drop(1).count { i -> faster[i] >= 0 && faster[i - 1] < 0 }
        assertEquals(200.0, crossings.toDouble() * DUB_SAMPLE_RATE / faster.size, 8.0)
        val tooLong = assertThrows(DubFailure::class.java) { fitSpeech(wav, 5_000, 12) }
        assertEquals(DubIssue.CUE_TOO_LONG, tooLong.issue)
        assertEquals(12, tooLong.line)
    }

    @Test fun previewUsesSavedWordsAndRebasesOnlyTheSample() {
        val plan = planDub(listOf(SubCue(1, 64.5, 65.2, "العيّنة"), SubCue(2, 66.0, 67.0, "وتاني سطر"),
            SubCue(3, 90.0, 92.0, "مش جزء من المعاينة")), 100.0)
        val sample = plan.preview()
        assertEquals(64_500L * 24, sample.originalStart)
        assertEquals(0L, sample.lines.first().start)
        assertEquals(1_500L * 24, sample.lines.last().start)
        assertEquals(2_500L * 24, sample.frames)
        assertEquals(listOf("العيّنة", "وتاني سطر"), sample.lines.map { it.text })
        assertEquals(64_500L * 24, plan.lines.first().start)
        assertEquals(100_000L * 24, plan.frames)
        assertEquals(DubIssue.TIMING, assertThrows(DubFailure::class.java) {
            planDub(listOf(SubCue(0, 1.0, .3, "غير صالح")))
        }.issue)
    }

    private fun writePcm16(file: File, samples: ShortArray) {
        RandomAccessFile(file, "rw").use { stream ->
            TimedDubWav(stream, samples.size.toLong()).apply { add(0L, samples); finish() }
        }
    }

    private fun inDirectory(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("kadr-dub-timeline").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
