package com.beeregg2001.komorebi.ui.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    application = android.app.Application::class,
    sdk = [28],
    qualifiers = "w960dp-h540dp-land-mdpi",
)
class PlayerCropOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun SemanticsNodeInteraction.pressCenter() {
        requestFocus()
        performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
    }

    @Test
    fun menuMode_autoFocusesDirectAdjustButton_andSupportsDpadNavigation() {
        val crop = PlayerCropState().apply {
            isEnabled = true
            mode = PlayerCropMode.MENU
        }
        composeRule.setContent {
            KomorebiTheme {
                PlayerLCropOverlay(
                    crop = crop,
                    onClose = { crop.mode = PlayerCropMode.HIDDEN },
                    inputProfile = LivePlayerCropInputProfile,
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CROP_DIRECT_ADJUST_BUTTON_TEST_TAG).assertIsFocused()

        composeRule.onNodeWithTag(CROP_DIRECT_ADJUST_BUTTON_TEST_TAG).performKeyInput {
            keyDown(Key.DirectionLeft)
            keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag(CROP_ORIGIN_BUTTON_TEST_TAG).assertIsFocused()

        composeRule.onNodeWithTag(CROP_ORIGIN_BUTTON_TEST_TAG).performKeyInput {
            keyDown(Key.DirectionLeft)
            keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag(CROP_ZOOM_IN_BUTTON_TEST_TAG).assertIsFocused()
    }

    @Test
    fun menuControls_updateZoomOriginPresetResetAndDisable() {
        val crop = PlayerCropState().apply {
            isEnabled = true
            mode = PlayerCropMode.MENU
        }
        var closedCount = 0
        composeRule.setContent {
            KomorebiTheme {
                PlayerLCropOverlay(
                    crop = crop,
                    onClose = {
                        closedCount++
                        crop.mode = PlayerCropMode.HIDDEN
                    },
                    inputProfile = RecordedPlayerCropInputProfile,
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CROP_ZOOM_IN_BUTTON_TEST_TAG).pressCenter()
        assertEquals(102f, crop.zoomPercent)

        composeRule.onNodeWithText("125%").pressCenter()
        assertEquals(125f, crop.zoomPercent)

        composeRule.onNodeWithTag(CROP_ORIGIN_BUTTON_TEST_TAG).pressCenter()
        assertEquals(PlayerZoomOrigin.BottomRight, crop.origin)

        composeRule.onNodeWithTag(CROP_RESET_BUTTON_TEST_TAG).pressCenter()
        assertEquals(100f, crop.zoomPercent)
        assertEquals(0f, crop.xPercent)
        assertEquals(0f, crop.yPercent)
        assertEquals(PlayerZoomOrigin.TopRight, crop.origin)

        composeRule.onNodeWithTag(CROP_DISABLE_BUTTON_TEST_TAG).pressCenter()
        assertFalse(crop.isEnabled)
        assertEquals(1, closedCount)
    }

    @Test
    fun directAdjustMode_showsProfileHints_andBackReturnsToMenuWithFocus() {
        val crop = PlayerCropState().apply {
            isEnabled = true
            mode = PlayerCropMode.MENU
        }
        composeRule.setContent {
            KomorebiTheme {
                PlayerLCropOverlay(
                    crop = crop,
                    onClose = { crop.mode = PlayerCropMode.HIDDEN },
                    inputProfile = RecordedPlayerCropInputProfile,
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CROP_DIRECT_ADJUST_BUTTON_TEST_TAG).pressCenter()
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()

        assertEquals(PlayerCropMode.DIRECT_ADJUST, crop.mode)
        composeRule.onNodeWithTag(CROP_DIRECT_ADJUST_HUD_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("早送り/巻戻し").assertIsDisplayed()

        composeRule.onNodeWithTag(CROP_DIRECT_ADJUST_HUD_TEST_TAG).performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()

        assertEquals(PlayerCropMode.MENU, crop.mode)
        assertTrue(crop.isEnabled)
        composeRule.onNodeWithTag(CROP_DIRECT_ADJUST_BUTTON_TEST_TAG).assertIsFocused()
    }
}
