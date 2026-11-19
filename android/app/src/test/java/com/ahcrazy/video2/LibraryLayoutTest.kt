package com.ahcrazy.video2

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.ui.library.LibraryScreen
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
class LibraryLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyArabicLibraryOffersVerificationAndRestoreWithoutASave() {
        compose.setContent {
            Video2Theme(rtl = true) {
                LibraryScreen(ar = true, onPlay = {}, onTranslate = {}, onReview = {}, onDub = {}, onConvert = {})
            }
        }
        compose.onNodeWithContentDescription("خيارات المكتبة").performClick()
        compose.onNodeWithTag("library.backup").performClick()
        compose.onNodeWithText("نسخ احتياطي واسترجاع").assertIsDisplayed()
        compose.onNodeWithTag("library.backup.save").assertIsNotEnabled()
        compose.onNodeWithTag("library.backup.verify").assertIsEnabled()
        compose.onNodeWithTag("library.backup.restore").assertIsEnabled()
    }

    @Test fun arabicLibraryResumesLatestVideoAndSearchHidesTheFeature() {
        val library = compose.activity.app().library
        val old = SavedVideo(id = "old", title = "مدينة هادئة", sourceUrl = "", localPath = "/missing/old.mp4",
            lastPositionSec = 16.0, durationSec = 90.0, lastPlayedAt = 20L)
        val recent = old.copy(id = "recent", title = "بعد المطر", localPath = "/missing/recent.mp4", lastPlayedAt = 30L)
        library.add(recent)
        library.add(old)
        var played: String? = null
        compose.setContent {
            Video2Theme(rtl = true) {
                LibraryScreen(ar = true, onPlay = { played = it.id }, onTranslate = {}, onReview = {}, onDub = {}, onConvert = {})
            }
        }
        compose.onNodeWithTag("library.import").assertIsDisplayed()
        compose.onNodeWithTag("library.resume").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(recent.id, played) }
        compose.onNodeWithTag("library.search").performTextInput("هادئة")
        compose.onNodeWithTag("library.resume").assertDoesNotExist()
        compose.onNodeWithText("مدينة هادئة").assertIsDisplayed()
        compose.onNodeWithText("بعد المطر").assertDoesNotExist()
    }
}
