package com.ahcrazy.video2.core.util

/**
 * Magic Query DSL — Android port of MagicQuery.swift.
 * One field accepts a name + commands in Arabic OR English:
 *   اسم الفيلم مدة:1:36:00 جودة:1080 موقع:site.com استبعد:trailer سنة:1995 مصدر:archive ترتيب:views min:20 max:90
 */
data class MagicQuery(
    val terms: String, val raw: String = "",
    val targetDuration: Double? = null, val minDuration: Double? = null, val maxDuration: Double? = null,
    val year: Int? = null, val hosts: List<String> = emptyList(), val preferredHeight: Int? = null,
    val excluded: List<String> = emptyList(), val sources: Set<String>? = null,
    val sort: Sort = Sort.RELEVANCE, val mustContain: List<String> = emptyList(),
    val directUrl: String? = null, val directUrlIsMedia: Boolean = false
) {
    enum class Sort { RELEVANCE, DURATION, VIEWS }
    val isEmpty: Boolean get() = terms.isBlank() && directUrl == null
    val webTerms: String get() = (terms + if (hosts.isEmpty()) "" else " " + hosts.joinToString(" OR ") { "site:$it" }).trim()

    companion object {
        private val arabicDigits = mapOf('٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4', '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9')
        fun normalizeDigits(s: String) = s.map { arabicDigits[it] ?: it }.joinToString("")

        /** "1:36:00" | "1:36" | "2h28m" | "1س30د" | "148" (minutes) → seconds */
        fun parseDuration(raw: String): Double? {
            val s = normalizeDigits(raw).trim().lowercase()
            if (s.isEmpty()) return null
            if (s.contains(":")) {
                val p = s.split(":").map { it.toDoubleOrNull() ?: return null }
                return when (p.size) { 3 -> p[0] * 3600 + p[1] * 60 + p[2]; 2 -> p[0] * 60 + p[1]; else -> null }
            }
            var total = 0.0; var matched = false
            Regex("""(\d+(?:\.\d+)?)\s*(h|س|ساعة|hr)""").find(s)?.let { total += it.groupValues[1].toDouble() * 3600; matched = true }
            Regex("""(\d+(?:\.\d+)?)\s*(m|د|دقيقة|min)""").find(s)?.let { total += it.groupValues[1].toDouble() * 60; matched = true }
            Regex("""(\d+(?:\.\d+)?)\s*(s|ث|sec)""").find(s)?.let { total += it.groupValues[1].toDouble(); matched = true }
            if (matched) return total
            return s.toDoubleOrNull()?.times(60) // bare number = minutes
        }

        private val keyAliases = mapOf(
            "مدة" to "duration", "duration" to "duration", "dur" to "duration",
            "جودة" to "quality", "جوده" to "quality", "quality" to "quality", "hd" to "quality",
            "موقع" to "site", "site" to "site",
            "استبعد" to "exclude", "exclude" to "exclude",
            "سنة" to "year", "سنه" to "year", "year" to "year",
            "مصدر" to "source", "source" to "source", "src" to "source",
            "ترتيب" to "sort", "sort" to "sort",
            "min" to "min", "max" to "max", "أدنى" to "min", "أقصى" to "max"
        )
        private val sourceAliases = mapOf(
            "archive" to "archive", "ar" to "archive", "أرشيف" to "archive",
            "youtube" to "youtube", "yt" to "youtube", "يوتيوب" to "youtube",
            "dailymotion" to "dailymotion", "dm" to "dailymotion", "داليموشن" to "dailymotion",
            "peertube" to "peertube", "pt" to "peertube",
            "vimeo" to "vimeo", "vm" to "vimeo", "فيميو" to "vimeo",
            "web" to "web", "ويب" to "web", "كل" to "web"
        )

        fun parse(input: String): MagicQuery {
            val normalized = normalizeDigits(input)
            val trimmed = normalized.trim()
            // Direct URL pasted?
            if (trimmed.startsWith("http") && !trimmed.contains(" ")) {
                val isMedia = com.ahcrazy.video2.core.model.MediaKind.infer(trimmed, null) !=
                    com.ahcrazy.video2.core.model.MediaKind.OTHER || trimmed.contains(".m3u8")
                return MagicQuery(terms = "", raw = input, directUrl = trimmed, directUrlIsMedia = isMedia)
            }
            var work = trimmed
            val quoted = Regex("\"([^\"]+)\"").findAll(work).map { it.groupValues[1] }.toList()
            work = work.replace(Regex("\"[^\"]+\""), " ")

            var target: Double? = null; var min: Double? = null; var max: Double? = null
            var year: Int? = null; var height: Int? = null; var sort = Sort.RELEVANCE
            val hosts = mutableListOf<String>(); val excluded = mutableListOf<String>()
            val sources = mutableSetOf<String>(); val rest = mutableListOf<String>()

            for (token in work.split(Regex("\\s+"))) {
                if (token.isBlank()) continue
                val kv = token.split(":", "=", limit = 2)
                val cmd = if (kv.size == 2) keyAliases[kv[0].lowercase()] else null
                if (cmd == null) { rest += token; continue }
                val v = kv[1]
                when (cmd) {
                    "duration" -> target = parseDuration(v)
                    "min" -> min = parseDuration(v)
                    "max" -> max = parseDuration(v)
                    "year" -> year = v.filter { it.isDigit() }.toIntOrNull()
                    "quality" -> height = v.filter { it.isDigit() }.toIntOrNull()
                    "site" -> hosts += v.split("|").filter { it.isNotBlank() }
                    "exclude" -> excluded += v.split("|").filter { it.isNotBlank() }
                    "source" -> sources += v.split("|").mapNotNull { sourceAliases[it.lowercase()] }
                    "sort" -> sort = when (v.lowercase()) {
                        "views", "مشاهدات" -> Sort.VIEWS
                        "duration", "مدة" -> Sort.DURATION
                        else -> Sort.RELEVANCE
                    }
                }
            }
            return MagicQuery(
                terms = rest.joinToString(" "), raw = input, targetDuration = target,
                minDuration = min, maxDuration = max, year = year, hosts = hosts,
                preferredHeight = height, excluded = excluded,
                sources = if (sources.isEmpty()) null else sources, sort = sort, mustContain = quoted
            )
        }
    }
}

object MagicDuration {
    enum class Match { MATCH, NEAR, TRUNCATED, UNKNOWN }
    fun matchOf(resultSec: Double?, q: MagicQuery): Match {
        val r = resultSec ?: return Match.UNKNOWN
        val target = q.targetDuration ?: return Match.UNKNOWN
        val diff = kotlin.math.abs(r - target)
        return when {
            diff <= 90 -> Match.MATCH
            diff <= 600 -> Match.NEAR
            r < target - 600 -> Match.TRUNCATED
            else -> Match.NEAR
        }
    }
    fun inBounds(resultSec: Double?, q: MagicQuery): Boolean {
        val r = resultSec ?: return true
        if (q.minDuration != null && r < q.minDuration) return false
        if (q.maxDuration != null && r > q.maxDuration) return false
        return true
    }
    fun format(sec: Double?): String {
        if (sec == null || sec <= 0) return "—"
        val n = sec.toInt()
        return if (n >= 3600) String.format("%d:%02d:%02d", n / 3600, (n % 3600) / 60, n % 60)
        else String.format("%d:%02d", n / 60, n % 60)
    }
}
