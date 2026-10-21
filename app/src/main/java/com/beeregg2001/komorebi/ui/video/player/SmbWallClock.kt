package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.data.PlayerClockMode
import com.beeregg2001.komorebi.ui.player.PlayerCropMode
import com.beeregg2001.komorebi.ui.player.PlayerWallClock
import java.time.ZonedDateTime

@Composable
internal fun BoxScope.SmbWallClock(
    timeFormat: String,
    clockMode: PlayerClockMode,
    showControls: Boolean,
    isSubMenuOpen: Boolean,
    isProgramInfoOpen: Boolean,
    cropMode: PlayerCropMode,
    isPiPMode: Boolean,
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    // Local files have no news classification or broadcast end time.
    if (showControls && !isSubMenuOpen && !isProgramInfoOpen &&
        cropMode == PlayerCropMode.HIDDEN && !isPiPMode &&
        clockMode.showsCurrentTime(null)
    ) {
        PlayerWallClock(
            timeFormat = timeFormat,
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp).testTag("smb-wall-clock"),
            now = now,
        )
    }
}
