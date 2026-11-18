package com.ahcrazy.video2.data.offline

import android.os.SystemClock
import com.ahcrazy.video2.core.model.GpuFallback
import com.ahcrazy.video2.core.model.OfflinePerformance
import org.json.JSONObject

/** Monotonic time for one attempt. The speech producer and MT worker write separate fields;
 * the worker is joined before exporting the result or reading the report. */
internal class OfflineStats(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    enum class Stage { EXTRACT, CPU_SPEECH, GPU_SPEECH, TRANSLATE, EXPORT }
    private val start = clock()
    private val millis = LongArray(Stage.entries.size)
    private val generated = mutableSetOf<Int>()
    private val reused = mutableSetOf<Int>()
    private var first: Long? = null
    var audioMs = 0L
    var cpuWindows = 0
    var gpuWindows = 0
    var reusedSpeechWindows = 0
    var generationCalls = 0
    var resumed = false
    var reusedTranscript = false
    var sameLanguage = false
    var overlapped = false
    var overlapDeferred = false
    var gpuFallback: GpuFallback? = null

    fun <T> measure(stage: Stage, block: () -> T): T {
        val began = clock()
        try { return block() }
        finally { millis[stage.ordinal] += (clock() - began).coerceAtLeast(0) }
    }

    fun batch(index: Int, fresh: Boolean) {
        if (fresh) {
            generated.add(index)
            if (first == null) first = elapsed()
        } else reused.add(index)
    }

    private fun elapsed() = (clock() - start).coerceAtLeast(0)
    fun report() = OfflinePerformance(
        elapsedMs = elapsed(), audioMs = audioMs, extractMs = millis[Stage.EXTRACT.ordinal],
        speechCpuMs = millis[Stage.CPU_SPEECH.ordinal], speechGpuMs = millis[Stage.GPU_SPEECH.ordinal],
        translateMs = millis[Stage.TRANSLATE.ordinal], exportMs = millis[Stage.EXPORT.ordinal],
        cpuWindows = cpuWindows, gpuWindows = gpuWindows, reusedSpeechWindows = reusedSpeechWindows,
        generatedBatches = generated.size, reusedBatches = (reused - generated).size, generationCalls = generationCalls,
        firstBatchMs = first, resumed = resumed, reusedTranscript = reusedTranscript, sameLanguage = sameLanguage,
        overlapped = overlapped, overlapDeferred = overlapDeferred, gpuFallback = gpuFallback
    )
}

internal fun OfflinePerformance.toJson() = JSONObject().apply {
    put("elapsed", elapsedMs); put("audio", audioMs); put("extract", extractMs); put("cpuSpeech", speechCpuMs)
    put("gpuSpeech", speechGpuMs); put("translate", translateMs); put("export", exportMs)
    put("cpuWindows", cpuWindows); put("gpuWindows", gpuWindows); put("reusedSpeechWindows", reusedSpeechWindows)
    put("generatedBatches", generatedBatches); put("reusedBatches", reusedBatches); put("generationCalls", generationCalls)
    put("firstBatch", firstBatchMs ?: JSONObject.NULL); put("resumed", resumed); put("reusedTranscript", reusedTranscript)
    put("sameLanguage", sameLanguage); put("overlapped", overlapped); put("overlapDeferred", overlapDeferred)
    put("gpuFallback", gpuFallback?.name ?: JSONObject.NULL)
}

internal fun readOfflinePerformance(json: JSONObject?): OfflinePerformance? {
    json ?: return null
    fun ms(key: String) = json.optLong(key).coerceAtLeast(0)
    fun count(key: String) = json.optInt(key).coerceAtLeast(0)
    return OfflinePerformance(
        elapsedMs = ms("elapsed"), audioMs = ms("audio"), extractMs = ms("extract"),
        speechCpuMs = ms("cpuSpeech"), speechGpuMs = ms("gpuSpeech"), translateMs = ms("translate"), exportMs = ms("export"),
        cpuWindows = count("cpuWindows"), gpuWindows = count("gpuWindows"), reusedSpeechWindows = count("reusedSpeechWindows"),
        generatedBatches = count("generatedBatches"), reusedBatches = count("reusedBatches"), generationCalls = count("generationCalls"),
        firstBatchMs = if (json.isNull("firstBatch")) null else ms("firstBatch"), resumed = json.optBoolean("resumed"),
        reusedTranscript = json.optBoolean("reusedTranscript"), sameLanguage = json.optBoolean("sameLanguage"),
        overlapped = json.optBoolean("overlapped"), overlapDeferred = json.optBoolean("overlapDeferred"),
        gpuFallback = GpuFallback.entries.firstOrNull { it.name == json.optString("gpuFallback") }
    )
}
