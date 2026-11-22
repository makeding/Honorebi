package com.beeregg2001.komorebi.ui.home

import android.app.Application
import android.media.session.PlaybackState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import com.beeregg2001.komorebi.notification.ERROR_CODE_NOTIFICATION_ACCESS_REQUIRED
import com.beeregg2001.komorebi.notification.ExternalMediaSessionState
import com.beeregg2001.komorebi.notification.InternalNowPlayingState
import com.beeregg2001.komorebi.notification.LauncherMediaPillState
import com.beeregg2001.komorebi.notification.NotificationAccessStatus
import com.beeregg2001.komorebi.notification.NotificationCenterUiState
import com.beeregg2001.komorebi.notification.NotificationSettingsOpenResult
import com.beeregg2001.komorebi.notification.SystemNotificationItem
import com.beeregg2001.komorebi.ui.main.NetworkConnectionStatus
import com.beeregg2001.komorebi.ui.main.NetworkTransport
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LauncherSystemStripNotificationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun badgeCountFormatsOneThroughNineAndNinePlus() {
        assertEquals("1", formatNotificationBadgeCount(1))
        assertEquals("9", formatNotificationBadgeCount(9))
        assertEquals("9+", formatNotificationBadgeCount(10))
        assertEquals("9+", formatNotificationBadgeCount(42))
    }

    @Test
    fun compactModeCollapsesBellWhenCountIsZeroAndShowsBellWhenCountIsPositive() {
        val notificationCount = mutableIntStateOf(0)

        compose.setContent {
            KomorebiTheme {
                LauncherSystemStrip(
                    timeFormat = "24H",
                    mediaPillState = LauncherMediaPillState.None,
                    showInputSource = false,
                    networkConnectionStatus = NetworkConnectionStatus(NetworkTransport.WIFI, true),
                    canTakeFocus = true,
                    returnPlayerFocusRequester = FocusRequester(),
                    inputSourceFocusRequester = FocusRequester(),
                    wifiSettingsFocusRequester = FocusRequester(),
                    systemSettingsFocusRequester = FocusRequester(),
                    leftEdgeFocusRequester = FocusRequester(),
                    downFocusRequester = FocusRequester(),
                    onMediaPillClick = {},
                    onLaunchInputSourcePicker = {},
                    onLaunchWifiSettings = {},
                    onLaunchSystemSettings = {},
                    onHasFocusChanged = {},
                    onBack = {},
                    notificationCount = notificationCount.intValue,
                    isCompactStatusOnly = true,
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag(NOTIFICATION_BELL_BUTTON_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SYSTEM_SETTINGS_BUTTON_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NETWORK_STATUS_BUTTON_TEST_TAG).assertExists()
        compose.onNodeWithTag(CLOCK_BUTTON_TEST_TAG).assertExists()

        compose.runOnIdle { notificationCount.intValue = 3 }
        compose.waitForIdle()
        compose.onNodeWithTag(NOTIFICATION_BELL_BUTTON_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()

        compose.runOnIdle { notificationCount.intValue = 15 }
        compose.waitForIdle()
        compose.onNodeWithText("9+").assertIsDisplayed()
    }

    @Test
    fun mediaPillSwitchesBetweenNoneInternalAndExternalStates() {
        val pillState = mutableStateOf<LauncherMediaPillState>(LauncherMediaPillState.None)

        compose.setContent {
            KomorebiTheme {
                LauncherSystemStrip(
                    timeFormat = "24H",
                    mediaPillState = pillState.value,
                    showInputSource = false,
                    networkConnectionStatus = NetworkConnectionStatus(NetworkTransport.WIFI, true),
                    canTakeFocus = true,
                    returnPlayerFocusRequester = FocusRequester(),
                    inputSourceFocusRequester = FocusRequester(),
                    wifiSettingsFocusRequester = FocusRequester(),
                    systemSettingsFocusRequester = FocusRequester(),
                    leftEdgeFocusRequester = FocusRequester(),
                    downFocusRequester = FocusRequester(),
                    onMediaPillClick = {},
                    onLaunchInputSourcePicker = {},
                    onLaunchWifiSettings = {},
                    onLaunchSystemSettings = {},
                    onHasFocusChanged = {},
                    onBack = {},
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag(MEDIA_PILL_TEST_TAG).assertDoesNotExist()

        compose.runOnIdle { pillState.value = LauncherMediaPillState.InternalMiniPlayer() }
        compose.waitForIdle()
        compose.onNodeWithTag(MEDIA_PILL_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText("再生中").assertIsDisplayed()

        compose.runOnIdle {
            pillState.value = LauncherMediaPillState.ExternalMedia(
                sessionKey = "spotify:1",
                packageName = "com.spotify.tv.android",
                title = "Lo-Fi Beats",
                appLabel = "Spotify",
                isPlaying = true,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(MEDIA_PILL_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText("Lo-Fi Beats").assertIsDisplayed()
    }

    @Test
    fun drawerShowsPermissionCardInternalNowPlayingExternalMediaAndEmptyNotifications() {
        val externalSession = ExternalMediaSessionState(
            sessionKey = "ytm:1",
            packageName = "com.google.android.youtube.tvmusic",
            appLabel = "YouTube Music",
            title = "Night Drive",
            subtitle = "Synth Artist",
            playbackState = PlaybackState.STATE_PLAYING,
            isPlaying = true,
            isBuffering = false,
            isActiveForStrip = true,
            positionMs = 30_000L,
            durationMs = 180_000L,
            playbackSpeed = 1.0f,
            lastPositionUpdateTimeMs = 0L,
            canPlay = true,
            canPause = true,
            canSkipToNext = true,
            canSkipToPrevious = false,
            canStop = false,
        )
        val uiState = NotificationCenterUiState(
            accessStatus = NotificationAccessStatus.REQUIRED,
            isAccessGranted = false,
            notifications = emptyList(),
            externalMediaSessions = listOf(externalSession),
            adbGrantCommand = "adb shell cmd notification allow_listener com.beeregg2001.Honorebi/Service",
        )

        compose.setContent {
            KomorebiTheme {
                NotificationMediaDrawer(
                    uiState = uiState,
                    internalNowPlaying = InternalNowPlayingState(
                        title = "ニュース7",
                        subtitle = "NHK総合",
                    ),
                    openReason = NotificationDrawerOpenReason.BELL,
                    timeFormat = "24H",
                    onReturnToInternalPlayer = {},
                    onSendMediaAction = { _, _ -> },
                    onOpenMediaApp = {},
                    onOpenNotification = {},
                    onDismissNotification = {},
                    onClearAllNotifications = {},
                    onOpenNotificationSettings = { NotificationSettingsOpenResult.OPENED_LISTENER_SETTINGS },
                    onRefreshAccessStatus = {},
                    onDismiss = {},
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag(NOTIFICATION_DRAWER_TEST_TAG).assertExists()
        compose.onNodeWithTag(PERMISSION_CARD_TEST_TAG).assertExists()
        compose.onNodeWithText("通知・外部メディア連携が未許可です（$ERROR_CODE_NOTIFICATION_ACCESS_REQUIRED）").assertExists()
        compose.onNodeWithTag(INTERNAL_NOW_PLAYING_CARD_TEST_TAG).assertExists()
        compose.onNodeWithText("ニュース7").assertExists()
        compose.onNodeWithTag(NOTIFICATION_DRAWER_LIST_TEST_TAG).performScrollToKey("media_ytm:1")
        compose.onNodeWithText("Night Drive").assertExists()
        // All 4 transport buttons exist; unsupported SkipPrevious is disabled in-place
        compose.onNodeWithContentDescription("前へ").assertIsNotEnabled()
        compose.onNodeWithContentDescription("一時停止").assertIsEnabled()
        compose.onNodeWithContentDescription("次へ").assertIsEnabled()
        compose.onNodeWithContentDescription("アプリを開く").assertIsEnabled()
        compose.onNodeWithTag(NOTIFICATION_DRAWER_LIST_TEST_TAG).performScrollToKey("notifications_empty")
        compose.onNodeWithTag(EMPTY_NOTIFICATIONS_TEST_TAG).assertExists()
        compose.onNodeWithTag(FOOTER_CLEAR_ALL_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FOOTER_CLOSE_TEST_TAG).assertExists()
    }

    @Test
    fun drawerShowsClearAllAndDismissOnlyForClearableNotifications() {
        val clearableItem = SystemNotificationItem(
            key = "k1",
            packageName = "com.example.app",
            appLabel = "Example",
            title = "更新完了",
            text = "アプリの更新が完了しました",
            postTimeMs = 1_700_000_000_000L,
            isClearable = true,
        )
        val ongoingItem = SystemNotificationItem(
            key = "k2",
            packageName = "com.example.vpn",
            appLabel = "VPN",
            title = "接続中",
            text = "VPN に接続されています",
            postTimeMs = 1_700_000_000_000L,
            isClearable = false,
        )
        val uiState = NotificationCenterUiState(
            accessStatus = NotificationAccessStatus.GRANTED,
            isAccessGranted = true,
            notifications = listOf(clearableItem, ongoingItem),
            externalMediaSessions = emptyList(),
        )

        compose.setContent {
            KomorebiTheme {
                NotificationMediaDrawer(
                    uiState = uiState,
                    internalNowPlaying = null,
                    openReason = NotificationDrawerOpenReason.BELL,
                    timeFormat = "24H",
                    onReturnToInternalPlayer = {},
                    onSendMediaAction = { _, _ -> },
                    onOpenMediaApp = {},
                    onOpenNotification = {},
                    onDismissNotification = {},
                    onClearAllNotifications = {},
                    onOpenNotificationSettings = { NotificationSettingsOpenResult.OPENED_LISTENER_SETTINGS },
                    onRefreshAccessStatus = {},
                    onDismiss = {},
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag(notificationRowCardTestTag("k1")).assertExists()
        compose.onNodeWithTag(notificationRowDismissTestTag("k1")).assertExists()
        compose.onNodeWithTag(notificationRowCardTestTag("k2")).assertExists()
        compose.onNodeWithTag(notificationRowDismissTestTag("k2")).assertDoesNotExist()
        compose.onNodeWithTag(FOOTER_CLEAR_ALL_TEST_TAG).assertExists()
        compose.onNodeWithTag(FOOTER_CLOSE_TEST_TAG).assertExists()
    }
}
