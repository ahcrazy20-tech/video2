package com.ahcrazy.video2.core.model

/** Immutable per-job selection. Existing jobs default to the exact CPU path they started with. */
enum class OfflinePreset { CPU, VULKAN, OVERLAP;
    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: CPU }
}

enum class GpuFallback { UNAVAILABLE, HEADROOM, INTERRUPTED, ENGINE }

data class OfflinePerformance(
    val elapsedMs: Long,
    val audioMs: Long = 0,
    val extractMs: Long = 0,
    val speechCpuMs: Long = 0,
    val speechGpuMs: Long = 0,
    val translateMs: Long = 0,
    val exportMs: Long = 0,
    val cpuWindows: Int = 0,
    val gpuWindows: Int = 0,
    val reusedSpeechWindows: Int = 0,
    val generatedBatches: Int = 0,
    val reusedBatches: Int = 0,
    val generationCalls: Int = 0,
    val firstBatchMs: Long? = null,
    val resumed: Boolean = false,
    val reusedTranscript: Boolean = false,
    val sameLanguage: Boolean = false,
    val overlapped: Boolean = false,
    val overlapDeferred: Boolean = false,
    val gpuFallback: GpuFallback? = null
)
