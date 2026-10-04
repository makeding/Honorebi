package com.beeregg2001.komorebi

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.components.ExitDialog
import com.beeregg2001.komorebi.ui.main.MainRootScreen
import com.beeregg2001.komorebi.ui.screensaver.ScreensaverInteractionTracker
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var homeIntentVersion by mutableIntStateOf(0)

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

        setContent {
            KomorebiTheme {
                var showExitDialog by remember { mutableStateOf(false) }

                // アプリのメインナビゲーション
                MainRootScreen(
                    channelViewModel = channelViewModel,
                    epgViewModel = epgViewModel,
                    homeViewModel = homeViewModel,
                    recordViewModel = recordViewModel,
                    homeIntentVersion = homeIntentVersion,
                    onRemotePlaybackOpened = ::showRemotePlayback,
                    onExitApp = { showExitDialog = true }
                )

                if (showExitDialog) {
                    ExitDialog(
                        onConfirm = { finish() },
                        onDismiss = { showExitDialog = false }
                    )
                }
            }
        }
    }

    private fun showRemotePlayback() {
        getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeIntentVersion++
        }
    }
}
