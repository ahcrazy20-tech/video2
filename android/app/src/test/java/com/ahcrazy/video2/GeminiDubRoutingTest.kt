package com.ahcrazy.video2

import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.data.dubbing.GeminiDubModel
import com.ahcrazy.video2.data.dubbing.GeminiDubRouter
import com.ahcrazy.video2.data.dubbing.GeminiHttpFailure
import com.ahcrazy.video2.data.dubbing.GeminiQuotaKind
import com.ahcrazy.video2.data.dubbing.GeminiRoutingEvent
import com.ahcrazy.video2.data.dubbing.GeminiTask
import com.ahcrazy.video2.data.dubbing.geminiQuota
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class GeminiDubRoutingTest {
    @Test fun dailyAndZeroLimitsIgnoreRetryInfoAndPinAnAllowedAlternateOnlyForThatStage() = runBlocking<Unit> {
        for ((id, value, kind) in listOf(
            Triple("GenerateRequestsPerDayPerProjectPerModel-FreeTier", 20, GeminiQuotaKind.DAILY),
            Triple("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", 0, GeminiQuotaKind.ZERO))) {
            val quota = geminiQuota(details(id, value, "34s"), 1_000)
            assertEquals(kind, quota.kind)
            assertFalse(quota.projectWide)
            val models = mutableListOf<GeminiDubModel>()
            val changes = mutableListOf<GeminiRoutingEvent?>()
            val router = GeminiDubRouter(true, update = { changes += it }, pause = { error("Don't wait for a daily/zero quota") })
            val director = GeminiTask.SCENE
            suspend fun send(): GeminiDubModel = router.run(director) { model ->
                models += model
                if (model == director.primary) throw GeminiHttpFailure(DubIssue.GEMINI_LIMIT, null, model, 429, quota)
                model
            }
            assertEquals(director.alternate, send())
            assertEquals(director.alternate, send())
            assertEquals(listOf(director.primary, director.alternate, director.alternate), models)
            assertEquals(listOf(GeminiRoutingEvent.Switched(director, director.alternate)), changes)
            assertEquals(GeminiDubModel.SPEAK, router.run(GeminiTask.SPEECH) { it })
        }
    }

    @Test fun shortLimitsRespectTheLongestHintAndLearnRequestSpacingForLaterCues() = runBlocking<Unit> {
        val quota = geminiQuota(details("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", 15, "6.75s"), 7_000)
        assertEquals(GeminiQuotaKind.SHORT, quota.kind)
        assertEquals(7_000L, quota.waitMs)
        assertEquals(15, quota.rpm)
        var now = 0L
        val waits = mutableListOf<Long>()
        val calls = mutableListOf<GeminiDubModel>()
        val router = GeminiDubRouter(false, pause = { waits += it; now += it }, clock = { now })
        suspend fun send() = router.run(GeminiTask.SPEECH) { model ->
            calls += model
            if (calls.size == 1) throw GeminiHttpFailure(DubIssue.GEMINI_LIMIT, 17, model, 429, quota)
            model
        }
        assertEquals(GeminiDubModel.SPEAK, send())
        assertEquals(GeminiDubModel.SPEAK, send())
        assertEquals(List(3) { GeminiDubModel.SPEAK }, calls)
        assertEquals(listOf(7_250L, 4_250L), waits)
    }

    @Test fun projectAndAuthErrorsDontTriggerAnAlternateEvenWhenEnabled() = runBlocking<Unit> {
        val spend = geminiQuota(details("SpendLimitPerProject", 10, "2s"), null)
        assertTrue(spend.projectWide)
        assertEquals(GeminiQuotaKind.PROJECT, spend.kind)
        val sharedDaily = geminiQuota(details("GenerateRequestsPerDayPerProject", 5, "2s"), null)
        val task = GeminiTask.SCENE
        val cases = listOf(
            GeminiHttpFailure(DubIssue.GEMINI_LIMIT, null, task.primary, 429, spend),
            GeminiHttpFailure(DubIssue.GEMINI_LIMIT, null, task.primary, 429, sharedDaily),
            GeminiHttpFailure(DubIssue.GEMINI_KEY_REJECTED, null, task.primary, 401),
            GeminiHttpFailure(DubIssue.GEMINI_AUTH, null, task.primary, 403),
            GeminiHttpFailure(DubIssue.GEMINI_PROJECT, null, task.primary, 402),
            DubFailure(DubIssue.GEMINI_BLOCKED), DubFailure(DubIssue.GEMINI_NETWORK), DubFailure(DubIssue.GEMINI_ANALYSIS)
        )
        for (expected in cases) {
            var count = 0
            val router = GeminiDubRouter(true, update = { error("Unexpected retry or alternate") })
            val result = runCatching { router.run(task) { count++; throw expected } }
            assertSame(expected, result.exceptionOrNull())
            assertEquals(1, count)
        }
    }

    @Test fun bothModelsFailingDoesNotCycleOrResetTheUserBillingState() = runBlocking<Unit> {
        val seen = mutableListOf<GeminiDubModel>()
        val task = GeminiTask.SPEECH
        val router = GeminiDubRouter(true, pause = { error("No retry hint") })
        val failure = runCatching {
            router.run(task) { model ->
                seen += model
                throw GeminiHttpFailure(DubIssue.GEMINI_LIMIT, 7, model, 429, geminiQuota(null, null))
            }
        }.exceptionOrNull() as GeminiHttpFailure
        assertEquals(listOf(task.primary, task.alternate), seen)
        assertEquals(task.alternate, failure.model)
        assertEquals(7, failure.line)
    }

    @Test fun anOptOutAndAnOverlongWaitNeverCauseExtraRequests() = runBlocking<Unit> {
        val task = GeminiTask.SPEECH
        val longWait = GeminiHttpFailure(DubIssue.GEMINI_LIMIT, 9, task.primary, 429, geminiQuota(null, 300_000))
        val missing = GeminiHttpFailure(DubIssue.GEMINI_MODEL, 9, task.primary, 404)
        for (expected in listOf(longWait, missing)) {
            var calls = 0
            val router = GeminiDubRouter(false, update = { error("Unexpected cooldown or alternate") })
            val result = runCatching { router.run(task) { calls++; throw expected } }
            assertSame(expected, result.exceptionOrNull())
            assertEquals(1, calls)
        }
    }

    @Test fun cancelDuringWaitDoesNotSendAgainOrSwitch() = runBlocking<Unit> {
        val waiting = CompletableDeferred<Unit>()
        val task = GeminiTask.SPEAKERS
        var count = 0
        val router = GeminiDubRouter(true, pause = { waiting.complete(Unit); awaitCancellation() })
        val job = launch {
            router.run(task) { model ->
                count++
                throw GeminiHttpFailure(DubIssue.GEMINI_LIMIT, null, model, 429, geminiQuota(null, 2_000))
            }
        }
        withTimeout(2_000) { waiting.await() }
        job.cancelAndJoin()
        assertEquals(1, count)
    }

    private fun details(id: String, value: Int, delay: String) = JSONArray("""[
        {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"$id","quotaValue":"$value"}]},
        {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"$delay"}
    ]""")
}
