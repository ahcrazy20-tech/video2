package com.ahcrazy.video2

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.ui.player.PlayerDeck
import com.ahcrazy.video2.ui.player.PlayerFrame
import com.ahcrazy.video2.ui.player.PlayerHeader
import com.ahcrazy.video2.ui.player.PlayerTransport
import com.ahcrazy.video2.ui.theme.Video2Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h680dp-port", application = Video2Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun arabicPortraitReservesSystemSpaceAndOnlyMirrorsNavigation() {
        val shown = mutableStateOf(true)
        var backJumps = 0
        var seeked = 0f
        showPlayer(ar = true, chrome = { shown.value }, onBackTen = { backJumps++ }, onScrub = { seeked = it })

        val root = bounds("player.root")
        val video = bounds("player.video")
        val header = bounds("player.header")
        val deck = bounds("player.deck")
        assertTrue(header.top >= root.top + 32.dp)
        assertTrue(deck.bottom <= root.bottom - 28.dp)
        assertTrue(bounds("player.back").left > bounds("player.tools").left)
        assertTrue(bounds("player.rewind").right <= bounds("player.forward").left)
        val timeline = bounds("player.timeline") // Slider semantics can extend beyond its actual layout slot.
        val elapsed = bounds("player.elapsed")
        val duration = bounds("player.duration")
        assertTrue("Timeline must read left to right: $elapsed, $timeline, $duration", elapsed.right <= timeline.left && timeline.right <= duration.left)
        compose.onNodeWithTag("player.seek").performTouchInput { click(Offset(width * .75f, center.y)) }
        compose.runOnIdle { assertTrue("A touch on the right should set later progress: $seeked", seeked > .5f) }
        assertTrue(compose.onNodeWithText("سطر ترجمة").getUnclippedBoundsInRoot().bottom <= deck.top)
        for (tag in listOf("player.speed", "player.mute", "player.captions", "player.rotate")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val target = bounds(tag)
            assertTrue("$tag must be at least 48 dp wide", target.right - target.left >= 48.dp)
        }
        compose.onNodeWithTag("player.rewind").performClick()
        compose.runOnIdle { assertEquals(1, backJumps); shown.value = false }
        compose.onNodeWithTag("player.header").assertDoesNotExist()
        assertEquals(video, bounds("player.video"))
    }

    @Test
    @Config(qualifiers = "w640dp-h300dp-land")
    fun shortLandscapeKeepsControlsInsideSideCutoutsAndUsesCompactDeck() {
        showPlayer(ar = false)
        val root = bounds("player.root")
        val header = bounds("player.header")
        val deck = bounds("player.deck")
        assertTrue(header.left >= root.left + 20.dp)
        assertTrue(header.right <= root.right - 12.dp)
        assertTrue(deck.left >= root.left + 20.dp)
        assertTrue(deck.right <= root.right - 12.dp)
        assertTrue(deck.bottom <= root.bottom - 28.dp)
        assertTrue(bounds("player.back").left < bounds("player.tools").left)
        compose.onNodeWithTag("player.seek").assertIsDisplayed()
        compose.onNodeWithTag("player.play").assertIsDisplayed()
        compose.onNodeWithTag("player.speed").assertDoesNotExist()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun showPlayer(ar: Boolean, chrome: () -> Boolean = { true }, onBackTen: () -> Unit = {}, onScrub: (Float) -> Unit = {}) {
        compose.setContent {
            Video2Theme(rtl = ar) {
                PlayerFrame(chromeVisible = chrome(), insets = WindowInsets(left = 20.dp, top = 32.dp, right = 12.dp, bottom = 28.dp),
                    video = {}, header = { PlayerHeader(ar, "A long video title that should fit even on narrow displays", "MP4 · 720p", "Saved file", {}, {}) },
                    center = { if (chrome()) PlayerTransport(ar, true, false, true, false, onBackTen, {}, {}) },
                    captions = { Text(if (ar) "سطر ترجمة" else "Caption line") },
                    deck = { compact -> PlayerDeck(ar, compact, 1_000, 64_000, .4f, true, false, "1×", false, true, true,
                        !ar, onScrub, {}, {}, {}, {}, {}) })
            }
        }
    }
}
