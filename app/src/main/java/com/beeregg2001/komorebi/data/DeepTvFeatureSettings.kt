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

data class DeepTvFeatureSettings(
    val watchNextEnabled: Boolean = true,
    val watchNextNextEpisodeEnabled: Boolean = true,
    val previewLiveEnabled: Boolean = true,
    val previewRecordingsEnabled: Boolean = true,
    val remoteShortcutKeysEnabled: Boolean = true,
    val remoteNumberTuningEnabled: Boolean = true,
    val osPipAutoEnterEnabled: Boolean = true,
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
