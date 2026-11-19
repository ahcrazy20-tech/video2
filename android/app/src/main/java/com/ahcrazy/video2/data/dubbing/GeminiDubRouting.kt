package com.ahcrazy.video2.data.dubbing

import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.media.DEFAULT_RATE_WAIT_MS
import com.ahcrazy.video2.core.media.MAX_AUTO_RATE_WAIT_MS
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** Allowlist from the Gemini Developer API Free Tier, not a promise of quota or price for a given key. */
internal enum class GeminiDubModel(val id: String) {
    TRANSCRIBE("gemini-3.5-transcribe"), DIRECT("gemini-3.8-flash"), SPEAK("gemini-3.8-flash-tts"),
    DIRECT_LITE("gemini-3.5-flash-lite"), SPEAK_LITE("gemini-3.8-flash-lite-tts"),
}

internal enum class GeminiTask(val primary: GeminiDubModel, val alternate: GeminiDubModel) {
    SPEAKERS(GeminiDubModel.TRANSCRIBE, GeminiDubModel.DIRECT_LITE),
    SCENE(GeminiDubModel.DIRECT, GeminiDubModel.DIRECT_LITE),
    SPEECH(GeminiDubModel.SPEAK, GeminiDubModel.SPEAK_LITE),
}

internal enum class GeminiQuotaKind { SHORT, DAILY, ZERO, PROJECT, UNKNOWN }
internal data class GeminiQuota(val kind: GeminiQuotaKind, val projectWide: Boolean, val waitMs: Long?, val rpm: Int?)

internal class GeminiHttpFailure(
    issue: DubIssue, line: Int?, val model: GeminiDubModel, val status: Int,
    val quota: GeminiQuota? = null, val retryAfterMs: Long? = null,
) : DubFailure(issue, line)

private const val RETRY_MARGIN_MS = 250L
private val retryDuration = Regex("[0-9]{1,20}(?:\\.[0-9]{1,9})?s")
internal fun JSONArray?.geminiObjects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)

internal fun JSONArray?.geminiErrorReasons(): List<String> = geminiObjects()
    .filter { row -> row.optString("@type").let { it.isEmpty() || it == "type.googleapis.com/google.rpc.ErrorInfo" } }
    .map { it.optString("reason") }

private val projectReasons = setOf("BILLING_DISABLED", "SPEND_LIMIT_EXCEEDED", "ACCOUNT_QUOTA_EXCEEDED")

/** Only typed fields influence retries. No server messages, key material or quota dimension values are displayed. */
internal fun geminiQuota(details: JSONArray?, headerWaitMs: Long?): GeminiQuota {
    val entries = details.geminiObjects()
    val quotas = entries.filter { it.optString("@type") == "type.googleapis.com/google.rpc.QuotaFailure" }
        .flatMap { it.optJSONArray("violations").geminiObjects() }
    fun JSONObject.limitId() = optString("quotaId").ifBlank { optString("quotaMetric") }
        .lowercase().filter(Char::isLetterOrDigit)
    val accountCap = quotas.any { "spend" in it.limitId() || "billing" in it.limitId() } ||
        details.geminiErrorReasons().any { it in projectReasons }
    val projectWide = accountCap || quotas.any { row ->
        val id = row.limitId()
        "perproject" in id && "permodel" !in id && row.optJSONObject("quotaDimensions")?.has("model") != true
    }
    val kind = when {
        accountCap -> GeminiQuotaKind.PROJECT
        quotas.any { it.optString("quotaValue").toLongOrNull() == 0L } -> GeminiQuotaKind.ZERO
        quotas.any { "perday" in it.limitId() || "daily" in it.limitId() } -> GeminiQuotaKind.DAILY
        quotas.any { "perminute" in it.limitId() || "persecond" in it.limitId() } -> GeminiQuotaKind.SHORT
        else -> GeminiQuotaKind.UNKNOWN
    }
    val advertised = entries.filter { it.optString("@type") == "type.googleapis.com/google.rpc.RetryInfo" }
        .mapNotNull { row ->
            row.optString("retryDelay").takeIf(retryDuration::matches)?.dropLast(1)?.toDoubleOrNull()
                ?.let { ceil(it * 1000).toLong() }
        }.maxOrNull()
    val waitMs = listOfNotNull(advertised, headerWaitMs).maxOrNull()
    val rpm = quotas.filter { "request" in it.limitId() && "perminute" in it.limitId() }
        .mapNotNull { it.optString("quotaValue").toIntOrNull()?.takeIf { n -> n in 1..60_000 } }.minOrNull()
    return GeminiQuota(kind, projectWide, waitMs, rpm)
}

internal sealed interface GeminiRoutingEvent {
    data class Waiting(val task: GeminiTask, val model: GeminiDubModel, val seconds: Int) : GeminiRoutingEvent
    data class Switched(val task: GeminiTask, val alternate: GeminiDubModel) : GeminiRoutingEvent
}

/** One router per open dubbing sheet; renders are serial. No key, provider, or billing changes. */
internal class GeminiDubRouter(
    private val allowAlternate: Boolean,
    private val update: suspend (GeminiRoutingEvent?) -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val selected = mutableMapOf<GeminiTask, GeminiDubModel>()
    private val spacing = mutableMapOf<GeminiDubModel, Long>()
    private val lastRequest = mutableMapOf<GeminiDubModel, Long>()

    suspend fun <T> run(task: GeminiTask, request: suspend (GeminiDubModel) -> T): T {
        val model = selected[task] ?: task.primary
        try { return attempt(task, model, request) }
        catch (e: GeminiHttpFailure) {
            // Don't mask billing, blocked content, authentication, invalid output or an ambiguous network failure.
            val eligible = when (e.issue) {
                DubIssue.GEMINI_MODEL -> e.status == 404
                DubIssue.GEMINI_SERVICE -> e.status == 503
                DubIssue.GEMINI_LIMIT -> e.status == 429 && e.quota?.projectWide == false
                else -> false
            }
            if (!allowAlternate || model != task.primary || !eligible) throw e
        }
        currentCoroutineContext().ensureActive()
        update(GeminiRoutingEvent.Switched(task, task.alternate))
        // Pin a working alternate for this stage so the next cue/part doesn't re-hit an exhausted model.
        return attempt(task, task.alternate, request).also { selected[task] = task.alternate }
    }

    private suspend fun <T> attempt(task: GeminiTask, model: GeminiDubModel, request: suspend (GeminiDubModel) -> T): T {
        suspend fun send(waitMs: Long = 0): T {
            currentCoroutineContext().ensureActive()
            val interval = spacing[model] ?: 0L
            val paced = lastRequest[model]?.let { interval - (clock() - it).coerceAtLeast(0) } ?: 0L
            val wait = maxOf(waitMs, paced)
            if (wait > 0) {
                update(GeminiRoutingEvent.Waiting(task, model, ((wait + 999) / 1000).toInt()))
                pause(wait)
                currentCoroutineContext().ensureActive()
                update(null)
            }
            lastRequest[model] = clock()
            return request(model)
        }
        try { return send() }
        catch (e: GeminiHttpFailure) {
            val quota = e.quota
            val advertised = when {
                e.issue == DubIssue.GEMINI_SERVICE && e.status == 503 -> e.retryAfterMs
                quota?.kind == GeminiQuotaKind.SHORT -> quota.waitMs ?: DEFAULT_RATE_WAIT_MS
                quota?.kind == GeminiQuotaKind.UNKNOWN -> quota.waitMs
                else -> null // A daily, zero, or billing quota can also carry a misleading short retry hint.
            } ?: throw e
            if (advertised > MAX_AUTO_RATE_WAIT_MS) throw e
            // Learn a pace only from an explicit requests/minute limit, not a token quota or a guessed tier.
            quota?.rpm?.let { rpm -> spacing[model] = maxOf(spacing[model] ?: 0L, (60_000L + rpm - 1) / rpm + RETRY_MARGIN_MS) }
            return send((advertised.coerceAtLeast(1_000) + RETRY_MARGIN_MS).coerceAtMost(MAX_AUTO_RATE_WAIT_MS))
        }
    }
}
