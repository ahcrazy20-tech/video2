package com.ahcrazy.video2.core.media

import com.ahcrazy.video2.core.model.MediaProblem

internal data class DownloadCooldown(val host: String, val untilMs: Long) {
    fun asMediaFailure() = MediaDownloadException("${MediaProblem.TEMPORARY.ar} (HTTP 429)",
        MediaProblem.TEMPORARY, 429, sourceHost = host, retryAtMs = untilMs)
}

/** Share real 429s across lookups, probes and transfers. Only host names and deadlines are retained. */
internal class DownloadCooldowns(
    private val group: (String) -> String = { it },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val holds = LinkedHashMap<String, DownloadCooldown>()

    @Synchronized fun active(vararg hosts: String?): DownloadCooldown? = hosts.asSequence().filterNotNull()
        .mapNotNull { holds[group(it)] }.filter { it.untilMs > now() }.maxByOrNull { it.untilMs }

    @Synchronized fun hold(owner: String?, host: String, retryAfter: Long?, deadline: Long? = null): DownloadCooldown {
        val time = now()
        val keys = listOfNotNull(owner, host).map(group).distinct()
        val existing = keys.mapNotNull(holds::get)
        // An already-classified exception (including a blocked local attempt) must not restart the timer.
        val until = deadline ?: retryDeadline(time, rateWaitMillis(retryAfter,
            repeated = existing.any { it.untilMs in (time - 600_000)..time }))
        val pause = existing.maxByOrNull { it.untilMs }?.takeIf { it.untilMs > until } ?: DownloadCooldown(host, until)
        keys.forEach { holds.remove(it); holds[it] = pause }
        while (holds.size > 64) holds.remove(holds.keys.first())
        return pause
    }
}
