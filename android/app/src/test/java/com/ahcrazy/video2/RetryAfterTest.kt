package com.ahcrazy.video2

import com.ahcrazy.video2.core.media.DownloadCooldowns
import com.ahcrazy.video2.core.media.rateWaitMillis
import com.ahcrazy.video2.core.media.retryAfterMillis
import com.ahcrazy.video2.core.media.retryDeadline
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RetryAfterTest {
    @Test fun acceptsSecondsAndDatesWithoutTruncatingTheRequestedTime() {
        val now = Instant.parse("2026-11-18T08:00:00Z").toEpochMilli()
        assertEquals(175_000L, retryAfterMillis(" 175 ", now) ?: -1L)
        assertEquals(100_000L, retryAfterMillis("Wed, 18 Nov 2026 08:01:40 GMT", now) ?: -1L)
        assertEquals(0L, retryAfterMillis("Wed, 18 Nov 2026 07:10:00 GMT", now) ?: -1L)
        assertNull(retryAfterMillis("-4", now))
        assertEquals(Long.MAX_VALUE, retryDeadline(now, retryAfterMillis("99999999999999999999999999", now)!!))
        assertEquals(60_000L, rateWaitMillis(null))
        assertEquals(2_000L, rateWaitMillis(0))
    }

    @Test fun unrelatedOriginsStayAvailableAndExistingDeadlinesAreNotShortenedOrReset() {
        var now = 1_000L
        val limits = DownloadCooldowns(now = { now })
        val first = limits.hold("resolver.example.net", "media.example.net", 150_000L)
        assertEquals(first, limits.active("resolver.example.net"))
        assertNull(limits.active("other.example.net"))
        now += 15_000L
        limits.hold("resolver.example.net", "media.example.net", null, first.untilMs)
        assertEquals(first, limits.active("media.example.net"))
        now += 135_000L
        assertNull(limits.active("resolver.example.net"))
    }
}
