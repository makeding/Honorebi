package com.beeregg2001.komorebi.ui.main

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UserFacingFailure
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.ui.epg.ProgramDetailMode
import com.beeregg2001.komorebi.ui.epg.ProgramDetailScreen
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class ReservationFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingAndFailureRetainTheConfirmationFocusAndBounds() {
        val pending = mutableStateOf(false)
        val failure = mutableStateOf<UserFacingFailure?>(null)
        var requests = 0
        compose.setContent {
            KomorebiTheme {
                DeleteConfirmationDialog("録画予約を削除", "この予約を削除しますか？",
                    isSubmitting = pending.value, failure = failure.value,
                    onConfirm = { requests++; pending.value = true }, onCancel = {})
            }
        }
        val confirm = compose.onNodeWithText(AppStrings.BUTTON_DELETE)
        confirm.requestFocus()
        confirm.assertIsFocused()
        val bounds = confirm.getUnclippedBoundsInRoot()
        confirm.performKeyInput { pressKey(Key.DirectionCenter) }
        val submitting = compose.onNodeWithText("処理中…")
        submitting.assertIsFocused().assertIsNotEnabled()
        submitting.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(1, requests)
            pending.value = false
            failure.value = UserFacingFailure("サーバーと通信できませんでした。接続を確認して再試行してください。", "RESERVATION_FAILED_NETWORK")
        }
        confirm.assertIsFocused().assertIsEnabled()
        assertEquals(bounds, confirm.getUnclippedBoundsInRoot())
        val error = compose.onNodeWithText("${failure.value!!.message} [${failure.value!!.code}]")
        error.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        error.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        confirm.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun epgFailureKeepsTheRecordActionAndShowsTheWholeReason() {
        val pending = mutableStateOf(false)
        val failure = mutableStateOf<UserFacingFailure?>(null)
        val start = OffsetDateTime.now().plusDays(1)
        val program = EpgProgram("program", "channel", title = "番組", description = "詳細",
            detail = null, start_time = start.toString(), end_time = start.plusHours(1).toString(),
            duration = 3600, is_free = true, genres = null, video_type = null,
            audio_type = null, audio_sampling_rate = null)
        compose.setContent {
            KomorebiTheme {
                ProgramDetailScreen(program, mode = ProgramDetailMode.EPG,
                    initialFocusRequester = remember { FocusRequester() },
                    onBackClick = {}, isRecordSubmitting = pending.value,
                    recordFailure = failure.value, onRecordClick = { pending.value = true })
            }
        }
        val action = compose.onNodeWithText("録画予約する")
        // The detail screen deliberately absorbs its opening key for 500 ms.
        compose.mainClock.advanceTimeBy(600)
        action.requestFocus()
        action.assertIsFocused()
        val bounds = action.getUnclippedBoundsInRoot()
        action.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("処理中…").assertIsFocused().assertIsNotEnabled()
        compose.runOnIdle {
            pending.value = false
            failure.value = UserFacingFailure("サーバーと通信できませんでした。接続を確認して再試行してください。", "RESERVATION_FAILED_NETWORK")
        }
        action.assertIsFocused().assertIsEnabled()
        assertEquals(bounds, action.getUnclippedBoundsInRoot())
        val layouts = mutableListOf<TextLayoutResult>()
        val error = compose.onNodeWithText("${failure.value!!.message} [${failure.value!!.code}]")
        error.assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
    }
}
