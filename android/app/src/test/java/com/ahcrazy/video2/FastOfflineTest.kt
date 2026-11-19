package com.ahcrazy.video2

import com.ahcrazy.video2.data.offline.BatchRetryGuide
import com.ahcrazy.video2.data.offline.OfflineModel
import com.ahcrazy.video2.data.offline.SUBTITLE_DELIMITER
import com.ahcrazy.video2.data.offline.translateLines
import org.junit.Assert.*
import org.junit.Test

class FastOfflineTest {
    @Test fun smallIsMultilingualAndDoesNotReplaceAStoredMediumModelId() {
        val model = OfflineModel.WHISPER_SMALL_Q5
        assertEquals("ggml-small-q5_1.bin", model.publisherName)
        assertEquals(190_085_487L, model.spec.bytes)
        assertEquals("whisper-small-q5-ae85e4a935d7.bin", model.installedName)
        assertEquals(model, OfflineModel.speechForJob(model.id))
        assertTrue(model.supportsFastSpeech && model.supportsStreamingSpeech)
        assertEquals(OfflineModel.WHISPER_MEDIUM_Q5, OfflineModel.speechForJob("whisper-medium-q5"))
        assertEquals(OfflineModel.WHISPER_LARGE, OfflineModel.speechForJob(null))
        assertEquals(listOf(model, OfflineModel.HYMT_LITE), OfflineModel.pack(model, OfflineModel.HYMT_LITE))
    }

    @Test fun skipsThreeRecurringBadLargeGroupsThenTriesTheSizeAgain() {
        val lines = (1..6).map { "Line $it" }
        val guide = BatchRetryGuide()
        val calls = mutableListOf<Int>()
        fun run(largeWorks: Boolean): List<String> = translateLines(lines, "Arabic", 5, retryGuide = guide) { prompt, _ ->
            val source = prompt.substringAfter("[Source Text]\n").split(SUBTITLE_DELIMITER).map(String::trim)
            calls += source.size
            if (source.size == 6 && !largeWorks) "سطور مدمجة" else source.joinToString(SUBTITLE_DELIMITER) { "ترجمة $it" }
        }
        repeat(5) { assertEquals(lines.map { "ترجمة $it" }, run(false)) }
        assertEquals(listOf(6, 3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 3), calls)
        assertEquals(lines.map { "ترجمة $it" }, run(true))
        assertEquals(6, calls.last())
    }

    @Test fun fallsBackToDeferredGroupWhenTheSmallerFormCannotProduceAlignedLines() {
        val guide = BatchRetryGuide().apply { record(4, false); record(4, false) }
        val lines = (1..4).map { "Text $it" }
        val result = translateLines(lines, "Arabic", 7, retryGuide = guide) { prompt, _ ->
            val source = prompt.substringAfter("[Source Text]\n").split(SUBTITLE_DELIMITER).map(String::trim)
            if (source.size != 4) null else source.joinToString(SUBTITLE_DELIMITER) { "نص $it" }
        }
        assertEquals(lines.map { "نص $it" }, result)
        assertTrue(guide.tryGroup(4))
    }
}
