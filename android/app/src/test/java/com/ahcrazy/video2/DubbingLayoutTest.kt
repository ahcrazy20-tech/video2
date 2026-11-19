package com.ahcrazy.video2

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ahcrazy.video2.core.dubbing.DubVoice
import com.ahcrazy.video2.core.dubbing.DubVoices
import com.ahcrazy.video2.core.dubbing.azureDubVoices
import com.ahcrazy.video2.core.dubbing.planDub
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.data.SavedSourcePreview
import com.ahcrazy.video2.data.dubbing.DubSource
import com.ahcrazy.video2.ui.dubbing.DubbingPanel
import com.ahcrazy.video2.ui.dubbing.DubbingPanelState
import com.ahcrazy.video2.ui.dubbing.TightCueDialog
import com.ahcrazy.video2.ui.theme.Video2Theme
import com.ahcrazy.video2.ui.translate.SourceLanguageForm
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h680dp-port", application = Video2Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DubbingLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realAccentListAndManualSelectionReachTheSameVoiceInPreviewAndSave() {
        val sa = DubVoice("fixture-sa", "ar-SA", false, false, 300, 200)
        val eg = DubVoice("fixture-eg-with-a-long-engine-identifier-to-test-layout", "ar-EG", false, false, 300, 200)
        val missing = DubVoice("fixture-download", "ar-EG", false, true, 500, 100)
        val online = DubVoice("fixture-online", "ar-EG", true, false, 500, 100)
        val catalog = DubVoices(listOf(sa, eg, missing, online), sa.id, "fixture.tts", "Fixture speech engine")
        val dubSource = DubSource(planDub(listOf(SubCue(1, 3.0, 5.0, "عينّة محفوظة"))), "ar", true, File("sample.srt"), 0, 0)
        val heard = mutableListOf<DubVoice>()
        val saved = mutableListOf<DubVoice>()
        compose.setContent {
            Video2Theme(rtl = true) {
                DubbingPanel("عنوان عربي طويل لاختبار العرض الضيق", true, DubbingPanelState(dubSource, catalog),
                    onPreview = { heard.add(it) }, onStopPreview = {}, onSave = { saved.add(it) }, onCancelSave = {},
                    onChoiceChanged = {}, onRefresh = {}, onSettings = {}, onInstall = {})
            }
        }
        fun tap(tag: String) = compose.onNodeWithTag("dubbing.$tag").performScrollTo().assertIsDisplayed().performClick()
        tap("preview") // Default Auto selects the usable default engine voice.
        tap("dialect")
        compose.onNodeWithTag("dubbing.dialect.ar-MA").assertDoesNotExist()
        compose.onNodeWithTag("dubbing.dialect.ar-EG").assertIsDisplayed().performClick()
        tap("voice")
        compose.onNodeWithTag("dubbing.voice.${missing.id}").performClick()
        compose.onNodeWithTag("dubbing.preview").assertIsNotEnabled()
        compose.onNodeWithTag("dubbing.save").assertIsNotEnabled()
        tap("voice")
        compose.onNodeWithTag("dubbing.voice.${eg.id}").assertIsDisplayed().performClick()
        tap("preview")
        tap("save")
        compose.runOnIdle {
            assertEquals(listOf(sa, eg), heard)
            assertEquals(listOf(eg), saved)
        }
    }

    @Test fun azureRequiresSetupAndNewConsentAndKeepsTheAuditionedVoiceForSave() {
        val configured = mutableStateOf(false)
        val source = DubSource(planDub(listOf(SubCue(1, 3.0, 5.0, "صباح الخير"))), "ar", true, File("sample.srt"), 0, 0)
        val played = mutableListOf<DubVoice>()
        val saved = mutableListOf<DubVoice>()
        compose.setContent { Video2Theme(rtl = true) {
            DubbingPanel("عنوان", true, DubbingPanelState(source, null, azureConfigured = configured.value),
                onPreview = { played += it }, onStopPreview = {}, onSave = { saved += it }, onCancelSave = {},
                onChoiceChanged = {}, onRefresh = {}, onSettings = {}, onInstall = {})
        } }
        fun node(tag: String) = compose.onNodeWithTag("dubbing.$tag")
        fun tap(tag: String) = node(tag).performScrollTo().assertIsDisplayed().performClick()
        fun provider(id: String) {
            tap("provider")
            node("provider.$id").assertIsDisplayed().performClick()
        }
        provider("azure-neural")
        node("preview").assertIsNotEnabled()
        node("network").assertIsNotEnabled()
        compose.runOnIdle { configured.value = true }
        node("preview").assertIsNotEnabled()
        node("save").assertIsNotEnabled()
        tap("voice")
        node("voice.ar-EG-ShakirNeural").assertIsDisplayed().performClick()
        tap("network")
        tap("preview")
        tap("save")
        provider("android-tts")
        provider("azure-neural")
        node("network").assertIsOff()
        node("preview").assertIsNotEnabled()
        node("save").assertIsNotEnabled()
        compose.runOnIdle {
            val chosen = azureDubVoices.voices.first { it.id == "ar-EG-ShakirNeural" }
            assertEquals(listOf(chosen), played)
            assertEquals(played, saved)
        }
    }

    @Test fun tooLongCueAsksBeforeSilencingAnyLine() {
        val decisions = mutableListOf<Boolean>()
        compose.setContent { Video2Theme(rtl = true) { TightCueDialog(true, 30) { decisions += it } } }
        compose.onNodeWithText("السطر 30 أطول من وقته").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), decisions) }
        compose.onNodeWithText("كمّل وتخطّى").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(true), decisions) }
    }

    @Test fun sourceCorrectionNeedsLanguageAndExplicitConfirmation() {
        val started = mutableListOf<SubLang>()
        compose.setContent { Video2Theme(rtl = true) {
            SourceLanguageForm(true, SavedSourcePreview("sv", listOf("This is the saved English transcript.")),
                loading = false, busy = false) { started += it; true }
        } }
        compose.onNodeWithText("This is the saved English transcript.").assertIsDisplayed()
        compose.onNodeWithTag("translation.source.retry").assertIsNotEnabled()
        compose.onNodeWithText("لغة الكلام الصحيحة").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("الإنجليزية").assertIsDisplayed().performClick()
        compose.onNodeWithTag("translation.source.retry").assertIsNotEnabled()
        compose.onNodeWithTag("translation.source.confirm").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("translation.source.retry").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(SubLang.EN), started) }
    }
}
