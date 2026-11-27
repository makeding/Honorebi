package com.beeregg2001.komorebi.ui.setting

enum class SettingsCategory(
    val isUiGroupChild: Boolean = false,
) {
    GENERAL,
    CONNECTION,
    DEEP_TV,
    PLAYBACK,
    COMMENT,
    DISPLAY(isUiGroupChild = true),
    HOME(isUiGroupChild = true),
    LAUNCHER(isUiGroupChild = true),
    RECORDING(isUiGroupChild = true),
    EPG(isUiGroupChild = true),
    CACHE,
    APP_INFO;

    companion object {
        val SIDEBAR_ORDER: List<SettingsCategory> = listOf(
            GENERAL,
            CONNECTION,
            DEEP_TV,
            PLAYBACK,
            COMMENT,
            DISPLAY,
            HOME,
            LAUNCHER,
            RECORDING,
            EPG,
            CACHE,
            APP_INFO,
        )
    }
}

enum class SettingsFocusTarget(val category: SettingsCategory) {
    CONNECTION_SETUP(SettingsCategory.CONNECTION),
    COMMENT_ENGINE(SettingsCategory.COMMENT),
    APP_INFO_VERSION(SettingsCategory.APP_INFO),
    APP_INFO_DEVICE_CAPABILITIES(SettingsCategory.APP_INFO),
}

sealed interface SettingsDestination {
    val category: SettingsCategory

    data class Category(
        override val category: SettingsCategory = SettingsCategory.GENERAL,
    ) : SettingsDestination

    data class Item(
        val target: SettingsFocusTarget,
    ) : SettingsDestination {
        override val category: SettingsCategory get() = target.category
    }

    data object DeviceCapabilities : SettingsDestination {
        override val category: SettingsCategory get() = SettingsCategory.APP_INFO
    }

    companion object {
        val Default: SettingsDestination = Category(SettingsCategory.GENERAL)
    }
}

internal object ConnectionItemIndex {
    const val WEB_SETUP = 0
    const val COUNT = 8
}

internal object CommentItemIndex {
    const val DEFAULT_DISPLAY = 0
    const val SPEED = 1
    const val FONT_SIZE = 2
    const val OPACITY = 3
    const val MAX_LINES = 4
    const val LAYER_ORDER = 5
    const val COMMENT_ENGINE = 6
    const val WORD_COUNTER = 7
    const val COUNT = 8
}

internal object AppInfoItemIndex {
    const val VERSION = 0
    const val BETA_UPDATES = 1
    const val CHECK_UPDATES = 2
    const val PROJECT_GITHUB = 3
    const val OSS_LICENSES = 4
    const val DEVICE_CAPABILITIES = 5
    const val WHATS_NEW = 6
    const val COUNT = 7
}
