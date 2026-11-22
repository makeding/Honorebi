package com.beeregg2001.komorebi

import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.*
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.navigation.DeepLinkDispatcher
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.main.MainRootScreen
import com.beeregg2001.komorebi.ui.main.OsPipPolicy
import com.beeregg2001.komorebi.ui.screensaver.ScreensaverInteractionTracker
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import dagger.hilt.android.AndroidEntryPoint
import com.beeregg2001.komorebi.util.AppUpdater
import com.beeregg2001.komorebi.util.UpdateState
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appUpdater: AppUpdater
    @Inject lateinit var deepLinkDispatcher: DeepLinkDispatcher

    private var homeIntentVersion by mutableIntStateOf(0)
    private var isInOsPip by mutableStateOf(false)
    private var osPipDismissVersion by mutableIntStateOf(0)
    private var osPipEligible = false
    private var lastInternalLaunchUptime = 0L
    private var stoppedWhileInPip = false
    private var exitingOsPipAwaitingResume = false

    private val hasPipFeature: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !event.isScreensaverMediaKey()) {
            ScreensaverInteractionTracker.onUserInteraction()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ScreensaverInteractionTracker.onUserInteraction()
        return super.dispatchTouchEvent(event)
    }

    private fun KeyEvent.isScreensaverMediaKey(): Boolean = keyCode in setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_RECORD,
    )

    // Hiltが自動的にRepositoryを注入済みのViewModelを作成します
    // これらはlazyプロパティであり、アクセスされる（MainRootScreenに渡される）までインスタンス化されません。
    private val channelViewModel: ChannelViewModel by viewModels()
    private val epgViewModel: EpgViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()
    private val recordViewModel: RecordViewModel by viewModels()


    @UnstableApi
    // java.time は desugar により API 24 から利用可能にしている。
    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Komorebi)
        super.onCreate(savedInstanceState)

        val launchedFromDeepLink = savedInstanceState == null && deepLinkDispatcher.dispatch(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                appUpdater.updateState.collect { update ->
                    if (update is UpdateState.AwaitingInstallConfirmation) {
                        appUpdater.launchInstallConfirmation(this@MainActivity)
                    }
                }
            }
        }

        setContent {
            KomorebiTheme {
                // アプリのメインナビゲーション
                MainRootScreen(
                    channelViewModel = channelViewModel,
                    epgViewModel = epgViewModel,
                    homeViewModel = homeViewModel,
                    recordViewModel = recordViewModel,
                    homeIntentVersion = homeIntentVersion,
                    launchedFromDeepLink = launchedFromDeepLink,
                    deepLinkRequests = deepLinkDispatcher.requests,
                    onRemotePlaybackOpened = ::showRemotePlayback,
                    isInOsPip = isInOsPip,
                    osPipDismissVersion = osPipDismissVersion,
                    onOsPipEligibilityChanged = { osPipEligible = it },
                    onInternalActivityLaunch = { lastInternalLaunchUptime = SystemClock.uptimeMillis() },
                    onExitApp = ::finish
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        lastInternalLaunchUptime = SystemClock.uptimeMillis()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val msSinceInternalLaunch = if (lastInternalLaunchUptime > 0L) {
            SystemClock.uptimeMillis() - lastInternalLaunchUptime
        } else {
            null
        }
        val alreadyInPip = isInOsPip ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            OsPipPolicy.shouldEnter(
                sdkInt = Build.VERSION.SDK_INT,
                hasPipFeature = hasPipFeature,
                eligible = osPipEligible,
                alreadyInPip = alreadyInPip,
                msSinceInternalLaunch = msSinceInternalLaunch,
            )
        ) {
            runCatching {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val wasInPip = isInOsPip
        isInOsPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            stoppedWhileInPip = false
            exitingOsPipAwaitingResume = false
        } else {
            if (OsPipPolicy.isDismissal(
                    stillInPip = false,
                    atLeastStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                    stoppedWhileInPip = stoppedWhileInPip,
                )
            ) {
                osPipDismissVersion++
                exitingOsPipAwaitingResume = false
            } else if (wasInPip) {
                exitingOsPipAwaitingResume = true
            }
            stoppedWhileInPip = false
        }
    }

    override fun onResume() {
        super.onResume()
        exitingOsPipAwaitingResume = false
        stoppedWhileInPip = false
    }

    override fun onStop() {
        super.onStop()
        if (isInOsPip) {
            stoppedWhileInPip = true
        } else if (exitingOsPipAwaitingResume) {
            exitingOsPipAwaitingResume = false
            osPipDismissVersion++
        }
    }

    private fun showRemotePlayback() {
        getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeIntentVersion++
        } else {
            deepLinkDispatcher.dispatch(intent)
        }
    }
}
