package com.beeregg2001.komorebi.data

enum class TifNoSignalStyle(val label: String) {
    BANNER("標準バナー"),
    DVD_BOUNCE("DVD風バウンス"),
    HONOREBI_BOUNCE("Honorebi バウンス"),
    SCREENSAVER("スクリーンセーバー");

    companion object {
        fun fromPreference(value: String?): TifNoSignalStyle =
            entries.firstOrNull { it.name == value } ?: BANNER
    }
}
