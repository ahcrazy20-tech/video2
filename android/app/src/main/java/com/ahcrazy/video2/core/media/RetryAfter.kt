package com.ahcrazy.video2.core.media

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal const val DEFAULT_RATE_WAIT_MS = 60_000L
internal const val MAX_AUTO_RATE_WAIT_MS = 120_000L

/** Retry-After is either seconds or an HTTP date. Never shorten the server's requested wait. */
internal fun retryAfterMillis(raw: String?, now: Long = System.currentTimeMillis()): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    if (value.all { it in '0'..'9' }) {
        val seconds = value.toLongOrNull() ?: return Long.MAX_VALUE
        return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
    }
    return runCatching {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0)
    }.getOrNull()
}

internal fun rateWaitMillis(advertised: Long?, repeated: Boolean = false): Long =
    maxOf(if (repeated) DEFAULT_RATE_WAIT_MS * 2 else 2_000L, advertised ?: DEFAULT_RATE_WAIT_MS)

internal fun retryDeadline(now: Long, wait: Long): Long = now.coerceAtLeast(0).let { it + wait.coerceIn(0, Long.MAX_VALUE - it) }
