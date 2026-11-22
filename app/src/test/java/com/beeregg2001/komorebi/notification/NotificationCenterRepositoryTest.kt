package com.beeregg2001.komorebi.notification

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationCenterRepositoryTest {

    @Test
    fun downscaleBitmap_scalesLargeBitmapsToMax256AndPreservesSmallOnes() {
        val landscape = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        val scaledLandscape = downscaleBitmap(landscape, MAX_ARTWORK_DIMENSION_PX)
        assertEquals(256, scaledLandscape.width)
        assertEquals(128, scaledLandscape.height)

        val portrait = Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888)
        val scaledPortrait = downscaleBitmap(portrait, MAX_ARTWORK_DIMENSION_PX)
        assertEquals(128, scaledPortrait.width)
        assertEquals(256, scaledPortrait.height)

        val small = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val untouched = downscaleBitmap(small, MAX_ARTWORK_DIMENSION_PX)
        assertSame(small, untouched)
    }

    @Test
    fun openNotificationListenerSettings_fallsBackToSystemSettingsThenUnavailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = shadowOf(context.packageManager)
        val repo = NotificationCenterRepository(context)

        // 1. Neither settings intent resolves -> UNAVAILABLE + accessStatus updated
        var beforeLaunchCount = 0
        val unavailable = repo.openNotificationListenerSettings(context) { beforeLaunchCount++ }
        assertEquals(NotificationSettingsOpenResult.UNAVAILABLE, unavailable)
        assertEquals(NotificationAccessStatus.SETTINGS_UNAVAILABLE, repo.accessStatus.value)
        assertEquals(0, beforeLaunchCount)

        // 2. Only ACTION_SETTINGS resolves -> fallback succeeds
        val settingsActivity = ActivityInfo().apply {
            packageName = "com.android.tv.settings"
            name = "com.android.tv.settings.MainSettings"
        }
        shadowPm.addOrUpdateActivity(settingsActivity)
        shadowPm.addIntentFilterForActivity(
            android.content.ComponentName(settingsActivity.packageName, settingsActivity.name),
            IntentFilter(Settings.ACTION_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )

        val fallbackResult = repo.openNotificationListenerSettings(context) { beforeLaunchCount++ }
        assertEquals(NotificationSettingsOpenResult.OPENED_SYSTEM_SETTINGS, fallbackResult)
        assertEquals(1, beforeLaunchCount)

        // 3. ACTION_NOTIFICATION_LISTENER_SETTINGS resolves -> preferred intent wins
        val listenerActivity = ActivityInfo().apply {
            packageName = "com.android.tv.settings"
            name = "com.android.tv.settings.NotificationAccessActivity"
        }
        shadowPm.addOrUpdateActivity(listenerActivity)
        shadowPm.addIntentFilterForActivity(
            android.content.ComponentName(listenerActivity.packageName, listenerActivity.name),
            IntentFilter(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )

        val directResult = repo.openNotificationListenerSettings(context) { beforeLaunchCount++ }
        assertEquals(NotificationSettingsOpenResult.OPENED_LISTENER_SETTINGS, directResult)
        assertEquals(2, beforeLaunchCount)
    }

    @Test
    fun adbGrantCommand_containsPackageAndServiceClassName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NotificationCenterRepository(context)
        val expected =
            "adb shell cmd notification allow_listener ${context.packageName}/${KomorebiNotificationListenerService::class.java.name}"
        assertEquals(expected, repo.adbGrantCommand)
    }

    @Test
    fun openNotification_returnsFalseWhenContentIntentIsNull() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NotificationCenterRepository(context)
        var called = false
        val item = SystemNotificationItem(
            key = "k1",
            packageName = "com.example.app",
            appLabel = "Example",
            title = "Hello",
            text = "World",
            postTimeMs = 1L,
            isClearable = true,
            contentIntent = null,
        )
        assertFalse(repo.openNotification(item) { called = true })
        assertFalse(called)
    }
}
