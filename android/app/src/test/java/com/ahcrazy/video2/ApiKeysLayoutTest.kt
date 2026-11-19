package com.ahcrazy.video2

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.ahcrazy.video2.ui.settings.ApiKeysEntry
import com.ahcrazy.video2.ui.settings.ApiKeysScreen
import com.ahcrazy.video2.data.dubbing.GeminiDubModel
import com.ahcrazy.video2.ui.settings.GeminiKeyChecks
import com.ahcrazy.video2.ui.theme.Video2Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h680dp-port", application = Video2Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApiKeysLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowArabicSettingsShowKeysOnlyOnDemandAndEditOneProvider() {
        val store = ConcurrentHashMap(mapOf("groq" to "fixture-existing"))
        compose.setContent {
            var open by remember { mutableStateOf(false) }
            Video2Theme(rtl = true) {
                if (!open) ApiKeysEntry(ar = true) { open = true }
                else ApiKeysScreen(ar = true, store::containsKey, { id, key -> store[id] = key }, { store.remove(it) }) { open = false }
            }
        }
        compose.onNodeWithTag("settings.apiKeys.screen").assertDoesNotExist()
        compose.onNodeWithTag("settings.apiKeys.open").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings.apiKeys.search").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings.apiKeys.search").performTextInput("azure")
        compose.onNodeWithTag("settings.apiKeys.provider.groq").assertDoesNotExist()
        compose.onNodeWithTag("settings.apiKeys.input").assertDoesNotExist()
        compose.onNodeWithTag("settings.apiKeys.provider.azure").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings.apiKeys.input").performTextInput("fixture-new-key")
        compose.onNodeWithTag("settings.apiKeys.save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("المفتاح اتحفظ على الجهاز").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("fixture-new-key", store["azure"])
        assertEquals("fixture-existing", store["groq"])
        compose.onNodeWithTag("settings.apiKeys.back").performClick()
        compose.onNodeWithText("محفوظ").assertIsDisplayed()
        compose.onNodeWithTag("settings.apiKeys.provider.groq").assertDoesNotExist()
        compose.onNodeWithTag("settings.apiKeys.back").performClick()
        compose.onNodeWithTag("settings.apiKeys.open").assertIsDisplayed()
        compose.onNodeWithTag("settings.apiKeys.input").assertDoesNotExist()
    }

    @Test fun savedGeminiKeyIsNotTestedUntilRequestedAndSpeechNeedsConfirmation() {
        var keyChecks = 0
        var speechChecks = 0
        var missing = emptyList<GeminiDubModel>()
        compose.setContent { Video2Theme(rtl = true) {
            ApiKeysScreen(ar = true, { it == "gemini" }, { _, _ -> }, {}, initialProvider = "gemini",
                geminiChecks = GeminiKeyChecks(models = { keyChecks++; missing }, speech = { speechChecks++ })) {}
        } }
        fun node(tag: String) = compose.onNodeWithTag("settings.apiKeys.$tag")
        fun tap(tag: String) = node(tag).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings.apiKeys.testGemini").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(0, keyChecks); assertEquals(0, speechChecks) }
        tap("testGemini")
        node("testResult").assertTextContains("المفتاح صالح", substring = true)
        tap("testGeminiSpeech")
        compose.runOnIdle { assertEquals(1, keyChecks); assertEquals(0, speechChecks) }
        compose.onNodeWithText("اختبر الصوت").assertIsDisplayed().performClick()
        node("testResult").assertTextContains("اختبار الصوت نجح", substring = true)
        compose.runOnIdle {
            assertEquals(1, keyChecks); assertEquals(1, speechChecks)
            missing = listOf(GeminiDubModel.SPEAK_LITE)
        }
        tap("testGemini")
        node("testResult").assertTextContains("Google قبلت المفتاح", substring = true)
        node("testResult").assertTextContains(GeminiDubModel.SPEAK_LITE.id, substring = true)
        compose.runOnIdle { assertEquals(2, keyChecks); assertEquals(1, speechChecks) }
    }

}
