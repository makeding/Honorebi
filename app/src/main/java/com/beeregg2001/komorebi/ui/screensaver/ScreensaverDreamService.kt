package com.beeregg2001.komorebi.ui.screensaver

import android.service.dreams.DreamService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Android system DreamService path; the foreground app screensaver uses the same renderer. */
@AndroidEntryPoint
class ScreensaverDreamService : DreamService() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var upcomingRecordingsSource: UpcomingRecordingsSource

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val imagesJson by settingsRepository.screensaverImageUris.collectAsState(initial = "[]")
                val interval by settingsRepository.screensaverIntervalSeconds.collectAsState(initial = 30)
                val transition by settingsRepository.screensaverTransition.collectAsState(initial = "FADE")
                val showUpcoming by settingsRepository.screensaverShowUpcoming.collectAsState(initial = false)
                val images = rememberDreamImages(imagesJson)
                val upcoming = rememberUpcomingRecordings(
                    enabled = showUpcoming,
                    visible = true,
                    source = upcomingRecordingsSource,
                )
                KomorebiTheme {
                    ScreensaverContent(
                        imageUris = images,
                        intervalSeconds = interval,
                        transition = transition,
                        upcomingRecordings = upcoming,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        })
    }

}

@androidx.compose.runtime.Composable
private fun rememberDreamImages(json: String): List<String> = androidx.compose.runtime.remember(json) {
    runCatching { com.google.gson.Gson().fromJson(json, Array<String>::class.java)?.toList().orEmpty() }
        .getOrDefault(emptyList())
}
