package com.ahcrazy.video2

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.ahcrazy.video2.ui.AppTab
import com.ahcrazy.video2.ui.AppTabs
import com.ahcrazy.video2.ui.browser.BrowserScreen
import com.ahcrazy.video2.ui.browser.BrowserSession
import com.ahcrazy.video2.ui.theme.Video2Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun browserStartsWithoutAWebViewAndBottomTabsOpenOnTouch() {
        val mounted = mutableSetOf<AppTab>()
        compose.setContent {
            var selected by remember { mutableStateOf(AppTab.BROWSER) }
            val browser = remember { BrowserSession() }
            DisposableEffect(browser) { onDispose { browser.destroy() } }
            Video2Theme(rtl = true) {
                AppTabs(selected, "ar", rememberSaveableStateHolder(), { selected = it }) { active ->
                    DisposableEffect(active) {
                        mounted.add(active)
                        onDispose { mounted.remove(active) }
                    }
                    if (active == AppTab.BROWSER) BrowserScreen(true, browser, null, {}, {})
                    else Text("Screen ${active.name}")
                }
            }
        }

        compose.onNodeWithTag("browser.address").assertIsDisplayed()
        compose.onNodeWithTag("browser.start").assertIsDisplayed()
        compose.onNodeWithTag("browser.viewport").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf(AppTab.BROWSER), mounted) }
        AppTab.entries.drop(1).forEach { tab ->
            compose.onNodeWithTag(tab.label).performTouchInput { click() }
            compose.onNodeWithTag(tab.label).assertIsSelected()
            compose.onNodeWithText("Screen ${tab.name}").assertIsDisplayed()
            compose.onNodeWithTag("browser.address").assertDoesNotExist()
            compose.runOnIdle { assertEquals(setOf(tab), mounted) }
        }
        compose.onNodeWithTag(AppTab.BROWSER.label).performTouchInput { click() }
        compose.onNodeWithTag("browser.start.direct").performTouchInput { click() }
        compose.onNodeWithText("تحميل من رابط مباشر").assertIsDisplayed()
    }

    @Test fun submittingTheAddressKeepsTheToolbarAndRestoresThePageAfterSwitchingTabs() {
        lateinit var session: BrowserSession
        compose.setContent {
            var selected by remember { mutableStateOf(AppTab.BROWSER) }
            val browser = remember { BrowserSession() }
            DisposableEffect(browser) {
                session = browser
                onDispose { browser.destroy() }
            }
            Video2Theme(rtl = true) {
                AppTabs(selected, "ar", rememberSaveableStateHolder(), { selected = it }) { active ->
                    if (active == AppTab.BROWSER) BrowserScreen(true, browser, null, {}, {})
                    else Text("Screen ${active.name}")
                }
            }
        }

        val address = "https://example.invalid/watch?id=7"
        compose.onNodeWithTag("browser.address").performTextInput(address)
        compose.onNodeWithTag("browser.go", useUnmergedTree = true).assertIsEnabled().performTouchInput { click() }
        compose.onNodeWithTag("browser.viewport").assertIsDisplayed()
        compose.onNodeWithTag("browser.address").assertIsDisplayed()
        compose.runOnIdle { assertEquals(address, session.current.url) }
        compose.onNodeWithTag(AppTab.DOWNLOADS.label).performTouchInput { click() }
        compose.onNodeWithTag("browser.viewport").assertDoesNotExist()
        compose.onNodeWithTag(AppTab.BROWSER.label).performTouchInput { click() }
        compose.onNodeWithTag("browser.viewport").assertIsDisplayed()
        compose.onNodeWithTag("browser.address").assertIsDisplayed()
        compose.runOnIdle { assertEquals(address, session.current.url) }
    }
}
