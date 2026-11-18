package com.ahcrazy.video2

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.data.ResolverType
import com.ahcrazy.video2.ui.browser.ExternalDownloadForm
import com.ahcrazy.video2.ui.browser.ExternalVideoChoice
import com.ahcrazy.video2.ui.theme.Video2Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h1000dp", application = Video2Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExternalDownloadFormTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun itRequiresConsentAgainWhenTheActiveServerChanges() {
        val video = YouTubeVideo.parse("https://youtu.be/AbCdEfG_123?si=shared")
        val endpoints = mutableStateOf(mapOf(ResolverType.PIPED to "https://first.example.net"))
        // No native dialog window, IME or network is needed to test the consent gate.
        compose.setContent {
            Video2Theme(rtl = true) {
                ExternalDownloadForm(true, ExternalVideoChoice(video, type = ResolverType.PIPED), endpoints.value, {},
                    resolve = { _, _, _, _ -> error("Opening the form or toggling consent must not send a request") },
                    check = { error("No source should have been checked") }, onDownload = { error("No source should be queued") })
            }
        }
        compose.onNodeWithTag("external.resolve").assertIsNotEnabled()
        compose.onNodeWithTag("external.consent").assertIsOff().performTouchInput { click() }
        compose.onNodeWithTag("external.resolve").assertIsEnabled()
        compose.runOnIdle { endpoints.value = mapOf(ResolverType.PIPED to "https://second.example.net") }
        compose.onNodeWithTag("external.consent").assertIsOff()
        compose.onNodeWithTag("external.resolve").assertIsNotEnabled()
    }
}
