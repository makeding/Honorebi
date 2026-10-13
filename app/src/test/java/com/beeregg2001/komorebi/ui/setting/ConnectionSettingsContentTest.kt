package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.ConnectionSource
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
class ConnectionSettingsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialQrStaysOpenAfterSaveAndRevokesItsLinkWhenFinished() {
        val session = com.beeregg2001.komorebi.setup.ConnectionSetupSession()
        var finishable by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var starts = 0
        var stops = 0
        compose.setContent {
            KomorebiTheme(AppTheme.AUTUMN) {
                if (visible) ConnectionSetupDialog("初期設定", "192.168.1.10",
                    com.beeregg2001.komorebi.setup.ConnectionSetupUiState(session),
                    onStart = { starts++ }, onStop = { stops++; session.revoke() },
                    onDismiss = { visible = false }, onManualInput = { visible = false },
                    initialSetup = true, canFinishInitialSetup = finishable)
            }
        }
        val finish = compose.onNodeWithText("設定を完了")
        val manual = compose.onNodeWithText("リモコンで手動入力")
        finish.assertIsNotEnabled()
        manual.assertIsFocused()
        val before = finish.getUnclippedBoundsInRoot()
        compose.runOnIdle { finishable = true }
        finish.assertIsEnabled()
        assertEquals(before, finish.getUnclippedBoundsInRoot())
        manual.assertIsFocused()
        assertTrue(session.accepts(session.token))
        assertEquals(1, starts); assertEquals(0, stops)
        finish.performClick()
        compose.runOnIdle { assertFalse(session.accepts(session.token)); assertEquals(1, stops) }
    }

    @Test fun sourceDetailsAccountAndRemoteBackPreserveOriginAndMainRowBounds() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking {
            repository.saveString(SettingsRepository.SMB_SERVER_LIST, "[]")
            repository.saveString(SettingsRepository.KONOMI_IP, "")
            repository.saveString(SettingsRepository.MIRAKURUN_IP, "")
            repository.saveString(SettingsRepository.EDCB_IP, "192.168.1.20")
        }
        var back: OnBackPressedDispatcher? = null
        var selected: List<ConnectionSource> = emptyList()
        var accountClicks = 0
        var webSource: ConnectionSource? = null
        compose.setContent {
            KomorebiTheme(AppTheme.AUTUMN) {
                back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val scroll = rememberScrollState()
                val preferences = rememberSettingPreferences(repository)
                val requesters = remember { List(8) { FocusRequester() } }
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    ConnectionSettingsContent(preferences, "huggy", { accountClicks++ }, {}, {}, emptyList(), {},
                        { _, _ -> }, { webSource = it }, { selected = it }, {}, {}, requesters,
                        remember { FocusRequester() }, scroll, false, {})
                }
            }
        }
        fun row(text: String) = compose.onNode(hasText(text) and hasClickAction() and
            if (text == "メインシステム") hasText(text) else !hasText("メインシステム"))
        compose.waitUntil { compose.onAllNodes(hasText("EDCB") and hasClickAction()).fetchSemanticsNodes().size == 2 }
        row("メインシステム").assertIsNotEnabled()
        val mainBounds = row("メインシステム").getUnclippedBoundsInRoot()
        row("Mirakurun").requestFocus().performKeyInput { pressKey(Key.DirectionDown) }
        row("優先ソース").assertIsFocused()
        row("KonomiTV").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        row("スマホで設定").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(ConnectionSource.KONOMITV, webSource)
        row("ログアウト").performScrollTo().requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, accountClicks)
        compose.runOnIdle { back!!.onBackPressed() }
        row("KonomiTV").assertIsFocused()
        compose.onAllNodesWithText("HonomiTV アカウント").assertCountEquals(0)
        assertEquals(mainBounds, row("メインシステム").getUnclippedBoundsInRoot())
        row("EDCB").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        row("HTTP/HTTPSポート").assertExists()
        row("録画ファイルの再生方式").assertExists()
        compose.runOnIdle { back!!.onBackPressed() }
        row("EDCB").assertIsFocused()
        runBlocking { repository.saveString(SettingsRepository.KONOMI_IP, "https://tv.example.org") }
        compose.waitUntil { row("メインシステム").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        assertEquals(mainBounds, row("メインシステム").getUnclippedBoundsInRoot())
        row("メインシステム").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(ConnectionSource.KONOMITV, ConnectionSource.EDCB), selected)
    }
}
