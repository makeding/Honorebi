package com.beeregg2001.komorebi

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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
    private var settingsIntentVersion by mutableIntStateOf(0)
    var cecHomeAction: (() -> Unit)? = null
    var cecSettingsAction: (() -> Unit)? = null
    var cecKeyAction: ((KeyEvent) -> Boolean)? = null
    private val homeKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "moe.huggy.tvtweaks.action.HOME" -> {
                    context?.let(com.beeregg2001.komorebi.ui.setting.LauncherEnvironmentDetector::markTvTweaksHomeActive)
                    val cecAction = cecHomeAction
                    if (cecAction != null) cecAction() else homeIntentVersion++
                }
                "moe.huggy.tvtweaks.action.SETTINGS" -> {
                    val cecAction = cecSettingsAction
                    if (cecAction != null) cecAction() else settingsIntentVersion++
                }
            }
        }
    }
    private var isInOsPip by mutableStateOf(false)
    private var osPipDismissVersion by mutableIntStateOf(0)
    private var osPipEligible = false
    private var osPipAutoEnter = false
    private var osPipIsPlaying = false
    private var osPipCanSeek = false
    private var pipTogglePlayPause: (() -> Unit)? = null
    private var pipSeekRelativeMs: ((Long) -> Unit)? = null
    private var lastInternalLaunchUptime = 0L
    private var stoppedWhileInPip = false
    private var exitingOsPipAwaitingResume = false

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> pipTogglePlayPause?.invoke()
                ACTION_PIP_REWIND -> pipSeekRelativeMs?.invoke(-10_000L)
                ACTION_PIP_FORWARD -> pipSeekRelativeMs?.invoke(10_000L)
            }
        }
    }

    private val hasPipFeature: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (cecKeyAction?.invoke(event) == true) return true
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
        val tvTweaksFilter = IntentFilter().apply {
            addAction("moe.huggy.tvtweaks.action.HOME")
            addAction("moe.huggy.tvtweaks.action.SETTINGS")
        }
        ContextCompat.registerReceiver(this, homeKeyReceiver,
            tvTweaksFilter, ContextCompat.RECEIVER_EXPORTED)

        val launchedFromDeepLink = savedInstanceState == null && deepLinkDispatcher.dispatch(intent)

        val pipFilter = IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_REWIND)
            addAction(ACTION_PIP_FORWARD)
        }
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            pipFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

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
                    settingsIntentVersion = settingsIntentVersion,
                    launchedFromDeepLink = launchedFromDeepLink,
                    deepLinkRequests = deepLinkDispatcher.requests,
                    onRemotePlaybackOpened = ::showRemotePlayback,
                    isInOsPip = isInOsPip,
                    osPipDismissVersion = osPipDismissVersion,
                    onOsPipStateChanged = { eligible, autoEnter, isPlaying, canSeek ->
                        osPipEligible = eligible
                        osPipAutoEnter = autoEnter
                        osPipIsPlaying = isPlaying
                        osPipCanSeek = canSeek
                        updatePictureInPictureParams()
                    },
                    onRegisterPipTransportActions = { togglePlayPause, seekRelativeMs ->
                        pipTogglePlayPause = togglePlayPause
                        pipSeekRelativeMs = seekRelativeMs
                    },
                    onInternalActivityLaunch = { lastInternalLaunchUptime = SystemClock.uptimeMillis() },
                    onExitApp = ::finish
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(homeKeyReceiver)
        runCatching { unregisterReceiver(pipActionReceiver) }
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPictureInPictureParams(): PictureInPictureParams {
        val actions = buildList {
            if (osPipCanSeek) {
                add(
                    RemoteAction(
                        Icon.createWithResource(this@MainActivity, android.R.drawable.ic_media_rew),
                        "10秒戻す",
                        "10秒戻す",
                        PendingIntent.getBroadcast(
                            this@MainActivity,
                            REQ_PIP_REWIND,
                            Intent(ACTION_PIP_REWIND).setPackage(packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                )
            }
            add(
                RemoteAction(
                    Icon.createWithResource(
                        this@MainActivity,
                        if (osPipIsPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    ),
                    if (osPipIsPlaying) "一時停止" else "再生",
                    if (osPipIsPlaying) "一時停止" else "再生",
                    PendingIntent.getBroadcast(
                        this@MainActivity,
                        REQ_PIP_PLAY_PAUSE,
                        Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            )
            if (osPipCanSeek) {
                add(
                    RemoteAction(
                        Icon.createWithResource(this@MainActivity, android.R.drawable.ic_media_ff),
                        "10秒進む",
                        "10秒進む",
                        PendingIntent.getBroadcast(
                            this@MainActivity,
                            REQ_PIP_FORWARD,
                            Intent(ACTION_PIP_FORWARD).setPackage(packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                )
            }
        }
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(osPipEligible && osPipAutoEnter && osPipIsPlaying)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    private fun updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasPipFeature) {
            runCatching { setPictureInPictureParams(buildPictureInPictureParams()) }
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
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S &&
            OsPipPolicy.shouldEnter(
                sdkInt = Build.VERSION.SDK_INT,
                hasPipFeature = hasPipFeature,
                eligible = osPipEligible && osPipAutoEnter && osPipIsPlaying,
                alreadyInPip = alreadyInPip,
                msSinceInternalLaunch = msSinceInternalLaunch,
            )
        ) {
            runCatching {
                enterPictureInPictureMode(buildPictureInPictureParams())
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
            val cecAction = cecHomeAction
            if (cecAction != null) cecAction() else homeIntentVersion++
        } else {
            deepLinkDispatcher.dispatch(intent)
        }
    }

    companion object {
        private const val ACTION_PIP_PLAY_PAUSE = "com.beeregg2001.komorebi.action.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_REWIND = "com.beeregg2001.komorebi.action.PIP_REWIND"
        private const val ACTION_PIP_FORWARD = "com.beeregg2001.komorebi.action.PIP_FORWARD"
        private const val REQ_PIP_PLAY_PAUSE = 4201
        private const val REQ_PIP_REWIND = 4202
        private const val REQ_PIP_FORWARD = 4203
    }
}
