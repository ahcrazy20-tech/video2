package com.ahcrazy.video2

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.data.ResolverFailure
import com.ahcrazy.video2.data.ResolverProblem
import com.ahcrazy.video2.data.ResolverType
import com.ahcrazy.video2.ui.browser.ExternalDownloadForm
import com.ahcrazy.video2.ui.browser.ExternalVideoChoice
import com.ahcrazy.video2.ui.theme.Video2Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExternalDownloadFormTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun changingServerRequiresFreshConsentAndDoesNotSendThePreviousServersKey() {
        val video = requireNotNull(YouTubeVideo.parse("https://youtu.be/AbCdEfG_123?si=private"))
        val requests = mutableListOf<Triple<String, String, String?>>()
        // Use a single window, with the production form but a local API fixture.
        compose.setContent {
            Video2Theme(rtl = true) {
                ExternalDownloadForm(true, ExternalVideoChoice(video), mapOf(ResolverType.COBALT to "https://first.example.net"), {},
                    resolve = { _, endpoint, source, key ->
                        requests += Triple(endpoint.toString(), source.watchUrl, key)
                        throw ResolverFailure(ResolverProblem.ACCESS)
                    }, check = { error("No source should have been checked") }, onDownload = { error("No source should be queued") })
            }
        }
        fun control(name: String) = compose.onNodeWithTag("browser.external").performScrollToNode(hasTestTag(name)).let {
            compose.onNodeWithTag(name)
        }
        control("external.resolve").assertIsNotEnabled()
        control("external.key").performTextInput("only-for-first")
        control("external.consent").performClick()
        control("external.resolve").assertIsEnabled()
        control("external.server").performTextReplacement("https://second.example.net")
        control("external.consent").assertIsOff()
        control("external.resolve").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, requests.size) }
        control("external.consent").performClick()
        control("external.resolve").assertIsEnabled().performClick()
        compose.runOnIdle {
            val entry = requests.single()
            assertEquals("https://second.example.net/", entry.first)
            assertEquals(video.watchUrl, entry.second)
            assertNull(entry.third)
        }
    }
}
