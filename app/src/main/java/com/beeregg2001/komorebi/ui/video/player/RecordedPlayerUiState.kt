package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/** One owner for transient panels and the focus returned when they close. */
internal class RecordedPlayerUiState {
    val mainFocusRequester = FocusRequester()
    val subMenuFocusRequester = FocusRequester()
    val playerControlsFocusRequester = FocusRequester()
    val infoFocusRequester = FocusRequester()

    var isProgramInfoOpen by mutableStateOf(false)
    var infoFromMenu by mutableStateOf(false)
    var infoFromButton by mutableStateOf(false)
    var isModernSettingsOpen by mutableStateOf(false)
    var isChapterListOpen by mutableStateOf(false)
    var isKeyframeGridOpen by mutableStateOf(false)
    var wasControlsVisible by mutableStateOf(false)
}
