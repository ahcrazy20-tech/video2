package com.ahcrazy.video2

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ahcrazy.video2.ui.settings.AppearancePicker
import com.ahcrazy.video2.ui.theme.Video2Appearance
import com.ahcrazy.video2.ui.theme.Video2Theme
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h680dp-port", application = Video2Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun swatchesChangeTheActualAppPaletteInArabic() {
        var renderedAccent = Color.Unspecified
        compose.setContent {
            var theme by remember { mutableStateOf(Video2Appearance.fromStored("unknown")) }
            Video2Theme(rtl = true, appearance = theme) {
                val accent = MaterialTheme.colorScheme.primary
                SideEffect { renderedAccent = accent }
                AppearancePicker(ar = true, selected = theme) { theme = it }
            }
        }
        compose.onNodeWithText("جلاكسي").assertIsSelected()
        val galaxy = compose.runOnIdle { renderedAccent }
        compose.onNodeWithText("سديم").performClick().assertIsSelected()
        val nebula = compose.runOnIdle { renderedAccent }
        compose.onNodeWithText("كلاسيك").performClick().assertIsSelected()
        compose.runOnIdle {
            assertNotEquals(galaxy, nebula)
            assertNotEquals(galaxy, renderedAccent)
            assertNotEquals(nebula, renderedAccent)
        }
    }
}
