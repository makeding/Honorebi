package com.beeregg2001.komorebi.ui.screensaver

import android.service.dreams.DreamService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.OffsetDateTime

/** Android system DreamService path; the foreground app screensaver uses the same renderer. */
@AndroidEntryPoint
class ScreensaverDreamService : DreamService() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var reserveProvider: ReserveProvider
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nextRecording = MutableStateFlow<String?>(null)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        serviceScope.launch {
            val reservations = runCatching { reserveProvider.getReserves().getOrNull().orEmpty() }.getOrDefault(emptyList())
            nextRecording.value = reservations.mapNotNull { item ->
                val start = runCatching { OffsetDateTime.parse(item.program.startTime) }.getOrNull()
                if (start != null && start.isAfter(OffsetDateTime.now())) start to item.program.title else null
            }.minByOrNull { it.first }?.let { (start, title) -> "${start.toLocalTime().toString().take(5)}  $title" }
        }
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val imagesJson by settingsRepository.screensaverImageUris.collectAsState(initial = "[]")
                val interval by settingsRepository.screensaverIntervalSeconds.collectAsState(initial = 30)
                val transition by settingsRepository.screensaverTransition.collectAsState(initial = "FADE")
                val recording by nextRecording.collectAsState()
                val images = rememberDreamImages(imagesJson)
                KomorebiTheme {
                    ScreensaverContent(images, interval, transition, recording, Modifier.fillMaxSize())
                }
            }
        })
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun rememberDreamImages(json: String): List<String> = androidx.compose.runtime.remember(json) {
    runCatching { com.google.gson.Gson().fromJson(json, Array<String>::class.java)?.toList().orEmpty() }
        .getOrDefault(emptyList())
}
