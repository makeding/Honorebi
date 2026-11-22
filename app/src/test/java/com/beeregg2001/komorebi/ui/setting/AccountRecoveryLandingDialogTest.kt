package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
class AccountRecoveryLandingDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landingPageShowsAccountSummaryCacheTipAndInvokesActions() {
        var imports = 0
        var freshStarts = 0
        val state = SettingsViewModel.AccountRecoveryState(
            isPending = true,
            userName = "komorebi_user",
            connectionLabels = listOf("KonomiTV (192.168.1.10:7000)"),
            isApplying = false,
        )

        compose.setContent {
            KomorebiTheme(AppTheme.AUTUMN) {
                AccountRecoveryLandingContent(
                    state = state,
                    onImportPrevious = { imports++ },
                    onStartFresh = { freshStarts++ },
                )
            }
        }

        compose.onNodeWithTag("account_recovery_landing").assertIsDisplayed()
        compose.onNodeWithText("アプリがピカピカになりました").assertIsDisplayed()
        compose.onNodeWithText("komorebi_user").assertIsDisplayed()
        compose.onNodeWithText("KonomiTV (192.168.1.10:7000)").assertIsDisplayed()
        compose.onNodeWithText("次回お困りのときは").assertIsDisplayed()

        compose.onNodeWithTag("account_recovery_import_button").performClick()
        assertEquals(1, imports)

        compose.onNodeWithTag("account_recovery_fresh_button").performClick()
        assertEquals(1, freshStarts)
    }
}
