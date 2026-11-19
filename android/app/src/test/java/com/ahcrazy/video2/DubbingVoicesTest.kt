package com.ahcrazy.video2

import com.ahcrazy.video2.core.dubbing.DubVoice
import com.ahcrazy.video2.core.dubbing.DubVoices
import org.junit.Assert.*
import org.junit.Test

class DubbingVoicesTest {
    private val saLocal = DubVoice("sa-local", "ar-SA", false, false, 300, 200)
    private val saQuality = DubVoice("sa-quality", "ar-SA", false, false, 400, 200)
    private val egMissing = DubVoice("eg-data", "ar-EG", false, true, 400, 100)
    private val egOnline = DubVoice("eg-online", "ar-EG", true, false, 500, 100)
    private val enLocal = DubVoice("en-local", "en-US", false, false, 400, 200)
    private val catalog = DubVoices(listOf(enLocal, saQuality, egMissing, egOnline, saLocal), "sa-local")

    @Test fun exposesOnlyRealDialectsAndDoesNotPickAnotherLanguageOrAccent() {
        assertEquals(listOf("ar", "en"), catalog.languages)
        assertEquals(listOf("ar-EG", "ar-SA"), catalog.dialects("ar"))
        assertEquals(listOf("en-US"), catalog.dialects("en"))
        assertEquals("ar-SA", catalog.initialDialect("ar"))
        assertEquals("", catalog.initialDialect("tr"))
        assertNull(catalog.resolve("ar-MA", null, allowNetwork = true))
        assertNull(catalog.resolve("en-US", "sa-local", allowNetwork = true))
    }

    @Test fun autoPicksReadyLocalAndManualNeverSilentlyFallsBack() {
        assertEquals(saLocal, catalog.resolve("ar-SA", null, allowNetwork = true))
        assertEquals(saQuality, catalog.resolve("ar-SA", "sa-quality", allowNetwork = false))
        assertNull(catalog.resolve("ar-EG", "eg-data", allowNetwork = true))
        assertNull(catalog.resolve("ar-EG", null, allowNetwork = false))
        assertNull(catalog.resolve("ar-EG", "eg-online", allowNetwork = false))
        assertEquals(egOnline, catalog.resolve("ar-EG", "eg-online", allowNetwork = true))
        assertEquals(egOnline, catalog.resolve("ar-EG", null, allowNetwork = true))
        assertNull(catalog.resolve("ar-SA", "removed", allowNetwork = false))
    }
}
