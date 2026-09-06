package com.beeregg2001.komorebi.ui.player

/**
 * Declares which existing player controls are backed by the current source. The UI keeps its
 * fixed slots and only disables unsupported actions; source modules never need to invent a
 * recorded-program model to obtain the common controls.
 */
data class PlaybackUiCapabilities(
    val programInfo: Boolean,
    val quickSelection: Boolean,
    val thumbnailGrid: Boolean,
    val chapters: Boolean,
    val audio: Boolean,
    val speed: Boolean,
    val subtitles: Boolean,
    val subtitleLanguage: Boolean,
    val quality: Boolean,
    val comments: Boolean,
    val crop: Boolean,
    val cmSkip: Boolean,
    val hdr: Boolean,
    val dataBroadcasting: Boolean,
) {
    companion object {
        val Recorded = PlaybackUiCapabilities(
            programInfo = true,
            quickSelection = true,
            thumbnailGrid = true,
            chapters = true,
            audio = true,
            speed = true,
            subtitles = true,
            subtitleLanguage = true,
            quality = true,
            comments = true,
            crop = true,
            cmSkip = true,
            hdr = true,
            dataBroadcasting = true,
        )

        val Smb = PlaybackUiCapabilities(
            programInfo = true,
            quickSelection = false,
            thumbnailGrid = false,
            chapters = false,
            audio = true,
            speed = true,
            subtitles = true,
            subtitleLanguage = true,
            quality = false,
            comments = false,
            crop = true,
            cmSkip = false,
            hdr = false,
            dataBroadcasting = false,
        )
    }
}
