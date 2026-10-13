package com.beeregg2001.komorebi.ui.video

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.data.api.interceptor.BackendApiException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
class LibraryFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedRefreshRetainsContentBoundsAndRetryFocusWithoutDuplicateRequests() {
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>("接続できません。（NETWORK）")
        var requests = 0
        var recoveryRequested = false
        val content = FocusRequester()
        compose.setContent {
            KomorebiTheme {
                Box(Modifier.size(800.dp, 450.dp)) {
                    Box(Modifier.fillMaxSize().padding(bottom = LibraryFeedbackHeight)
                        .testTag("content").focusRequester(content).focusable())
                    LibraryFeedback(true, loading, error, "録画一覧を読み込めませんでした", "録画番組がありません",
                        remember { FocusRequester() },
                        onRetry = { requests++; loading = true; error = null },
                        onRecovery = { recoveryRequested = true }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        val bounds = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
        compose.onNodeWithText("録画番組がありません").assertDoesNotExist()
        compose.onNodeWithTag("library-retry").requestFocus().press(Key.DirectionCenter)
        compose.onNodeWithTag("library-retry").assertIsFocused().press(Key.DirectionCenter)
        assertEquals(1, requests)
        compose.onNodeWithText("接続できません。（NETWORK）", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(bounds, compose.onNodeWithTag("content").getUnclippedBoundsInRoot())
        compose.runOnIdle { error = "応答がありません。（TIMEOUT）"; loading = false }
        compose.onNodeWithTag("library-retry").assertIsFocused().press(Key.DirectionCenter)
        assertEquals(2, requests)
        compose.runOnIdle { loading = false }
        compose.onNodeWithTag("library-retry").assertIsFocused().press(Key.DirectionCenter)
        compose.runOnIdle {
            assertTrue(recoveryRequested)
            assertEquals(2, requests)
            content.requestFocus()
        }
        compose.onNodeWithTag("content").assertIsFocused()
        compose.onNodeWithTag("library-retry").assertDoesNotExist()
        assertEquals(bounds, compose.onNodeWithTag("content").getUnclippedBoundsInRoot())
    }

    @Test fun emptyResultDoesNotOfferAnErrorRetry() {
        compose.setContent {
            KomorebiTheme {
                LibraryFeedback(false, false, null, "読み込めませんでした", "一致するファイルがありません",
                    remember { FocusRequester() }, {}, {}, Modifier.size(800.dp, 450.dp))
            }
        }
        compose.onNodeWithText("一致するファイルがありません").assertIsDisplayed()
        compose.onNodeWithTag("library-retry").assertDoesNotExist()
    }

    @Test fun backendFailureKeepsSpecificSafeReasonAndCodeWithoutLeakingEndpoint() {
        val message = libraryFailureMessage(BackendApiException("ACCESS_LOGIN_REQUIRED", 200,
            "https://secret.invalid/?token=private", "text/html", false, "Access 認証ページが返されました。"))
        assertTrue(message.contains("Access 認証ページ"))
        assertTrue(message.contains("ACCESS_LOGIN_REQUIRED / HTTP 200"))
        assertFalse(message.contains("secret.invalid"))
        assertFalse(message.contains("token"))
    }

    private fun SemanticsNodeInteraction.press(key: Key) = performKeyInput { keyDown(key); keyUp(key) }
}
