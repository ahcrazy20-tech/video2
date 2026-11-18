package com.ahcrazy.video2.core.util

import com.ahcrazy.video2.core.model.SubCue
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

/** SRT parse/write — port of SubtitleCodec.swift. */
object SrtCodec {
    fun srtTime(sec: Double): String {
        val ms = (sec.coerceAtLeast(0.0) * 1000).roundToLong()
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
    }

    private fun parseTime(s: String): Double {
        val p = s.trim().replace(',', '.').split(":")
        if (p.size != 3) return 0.0
        return (p[0].toDoubleOrNull() ?: 0.0) * 3600 + (p[1].toDoubleOrNull() ?: 0.0) * 60 + (p[2].toDoubleOrNull() ?: 0.0)
    }

    fun parse(text: String): List<SubCue> {
        val cues = mutableListOf<SubCue>()
        val blocks = text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val timeLine = lines.firstOrNull { it.contains("-->") } ?: continue
            val idx = lines.indexOf(timeLine)
            val parts = timeLine.split("-->")
            if (parts.size != 2) continue
            val body = lines.drop(idx + 1).joinToString("\n")
            cues += SubCue(cues.size, parseTime(parts[0]), parseTime(parts[1]), original = body)
        }
        return cues
    }

    fun parse(file: File): List<SubCue> = if (file.exists()) parse(file.readText()) else emptyList()

    /** mode: "original" | "translated" | "bilingual" */
    fun write(cues: List<SubCue>, mode: String): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, c ->
            val text = when (mode) {
                "translated" -> c.translated.ifBlank { c.original }
                "bilingual" -> listOf(c.original, c.translated).filter { it.isNotBlank() }.joinToString("\n")
                else -> c.original
            }
            sb.append(i + 1).append("\n")
                .append(srtTime(c.startSec)).append(" --> ").append(srtTime(c.endSec)).append("\n")
                .append(text).append("\n\n")
        }
        return sb.toString()
    }

    fun cueAt(cues: List<SubCue>, timeSec: Double, translated: Boolean = false): String? =
        cues.firstOrNull { timeSec >= it.startSec && timeSec <= it.endSec }
            ?.let { if (translated) it.translated.ifBlank { null } else it.original.ifBlank { null } }
}
