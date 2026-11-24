package com.beeregg2001.komorebi.data

enum class DeepTvFeature {
    WATCH_NEXT,
    WATCH_NEXT_NEXT_EPISODE,
    PREVIEW_LIVE,
    PREVIEW_RECORDINGS,
    REMOTE_SHORTCUT_KEYS,
    REMOTE_NUMBER_TUNING,
    OS_PIP_AUTO_ENTER,
}

enum class BackgroundPlaybackMode(
    val preferenceValue: String,
    val label: String,
) {
    OFF("OFF", "OFF"),
    PIP("PIP", "ピクチャー・イン・ピクチャー"),
    AUDIO_ONLY("AUDIO_ONLY", "バックグラウンド音声再生");

    fun next(pipSupported: Boolean): BackgroundPlaybackMode = when (this) {
        OFF -> if (pipSupported) PIP else AUDIO_ONLY
        PIP -> AUDIO_ONLY
        AUDIO_ONLY -> OFF
    }

    companion object {
        fun fromPreference(
            value: String?,
            legacyOsPipAutoEnter: Boolean,
        ): BackgroundPlaybackMode = when (value) {
            OFF.preferenceValue -> OFF
            PIP.preferenceValue -> PIP
            AUDIO_ONLY.preferenceValue -> AUDIO_ONLY
            else -> if (legacyOsPipAutoEnter) PIP else OFF
        }
    }
}

data class DeepTvFeatureSettings(
    val watchNextEnabled: Boolean = true,
    val watchNextNextEpisodeEnabled: Boolean = true,
    val previewLiveEnabled: Boolean = true,
    val previewRecordingsEnabled: Boolean = true,
    val remoteShortcutKeysEnabled: Boolean = true,
    val remoteNumberTuningEnabled: Boolean = true,
    val osPipAutoEnterEnabled: Boolean = true,
    val backgroundPlaybackMode: BackgroundPlaybackMode =
        if (osPipAutoEnterEnabled) BackgroundPlaybackMode.PIP else BackgroundPlaybackMode.OFF,
) {
    fun isEnabled(feature: DeepTvFeature): Boolean = when (feature) {
        DeepTvFeature.WATCH_NEXT -> watchNextEnabled
        DeepTvFeature.WATCH_NEXT_NEXT_EPISODE -> watchNextNextEpisodeEnabled
        DeepTvFeature.PREVIEW_LIVE -> previewLiveEnabled
        DeepTvFeature.PREVIEW_RECORDINGS -> previewRecordingsEnabled
        DeepTvFeature.REMOTE_SHORTCUT_KEYS -> remoteShortcutKeysEnabled
        DeepTvFeature.REMOTE_NUMBER_TUNING -> remoteNumberTuningEnabled
        DeepTvFeature.OS_PIP_AUTO_ENTER -> osPipAutoEnterEnabled
    }

    /** Effective: next-episode needs Watch Next on. */
    val nextEpisodeActive: Boolean
        get() = watchNextEnabled && watchNextNextEpisodeEnabled
}
