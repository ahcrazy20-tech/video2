package com.ahcrazy.video2

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.data.SmartCandidate
import com.ahcrazy.video2.data.SmartQuality
import com.ahcrazy.video2.ui.browser.DownloadIntentCards
import com.ahcrazy.video2.ui.browser.DownloadQualityRows
import com.ahcrazy.video2.ui.theme.Video2Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h680dp-port", application = Video2Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadQualityLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theQualityActionShowsActualChoicesAndNeverClicksAuto() {
        val choices = listOf(720, 360, null).map { height ->
            SmartQuality(SmartCandidate(DetectedMedia("https://media.example.net/$height.mp4", "Fixture", MediaKind.MP4,
                height = height), DownloadAuth.default))
        }
        var autoActions = 0
        compose.setContent {
            var listing by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf<String?>(null) }
            Video2Theme(rtl = true) {
                Column {
                    if (listing) DownloadQualityRows(true, choices, selected) { selected = it }
                    else DownloadIntentCards(true, 1080, { autoActions++ }, { listing = true })
                }
            }
        }
        compose.onNodeWithTag("smartDownload.auto").assertIsDisplayed()
        compose.onNodeWithTag("smartDownload.qualities").performClick()
        compose.onNodeWithTag("smartDownload.quality.360").performClick().assertIsSelected()
        compose.onNodeWithText("جودة المصدر").assertIsDisplayed()
        compose.onNodeWithTag("smartDownload.quality.1080").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, autoActions) }
    }
}
