package com.ahcrazy.video2

import com.ahcrazy.video2.data.offline.OfflineIssue
import com.ahcrazy.video2.data.offline.OfflineModel
import com.ahcrazy.video2.data.offline.chooseRapidWhisper
import com.ahcrazy.video2.data.offline.moonshineOrtIssueFor
import org.junit.Assert.*
import org.junit.Test

class MoonshineCompatibilityTest {
    @Test fun bundledOrtMustNotRunOnSmeWithoutSme2() {
        val sme = 1L shl 23
        val sme2 = 1L shl 37
        val otherFeatures = (1L shl 13) or (1L shl 36) or (1L shl 38)
        assertEquals(OfflineIssue.MOONSHINE_CPU, moonshineOrtIssueFor(sme))
        assertEquals(OfflineIssue.MOONSHINE_CPU, moonshineOrtIssueFor(sme or otherFeatures))
        assertNull(moonshineOrtIssueFor(sme or sme2 or otherFeatures))
        assertNull(moonshineOrtIssueFor(otherFeatures))
        assertNull(moonshineOrtIssueFor(0L)) // CPUs with no SME do not take that ORT kernel path.
        assertEquals(OfflineIssue.NOT_SUPPORTED, moonshineOrtIssueFor(-1L))
    }

    @Test fun safeEnglishRouteReusesInstalledWhisperAndFirefoxWithoutChoosingMoonshine() {
        val small = OfflineModel.WHISPER_SMALL_Q5
        val medium = OfflineModel.WHISPER_MEDIUM_Q5
        val moonshine = OfflineModel.MOONSHINE_EN
        assertEquals(medium, chooseRapidWhisper { it in setOf(medium, moonshine) })
        assertEquals(small, chooseRapidWhisper { it in setOf(medium, small, moonshine) })
        assertEquals(small, chooseRapidWhisper { it == moonshine })
        assertEquals(listOf(medium, OfflineModel.FIREFOX_EN_AR), OfflineModel.pack(chooseRapidWhisper { it == medium }, OfflineModel.FIREFOX_EN_AR))
    }
}
