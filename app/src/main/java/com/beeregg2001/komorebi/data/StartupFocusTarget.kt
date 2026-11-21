package com.beeregg2001.komorebi.data

enum class StartupFocusTarget(val label: String) {
    SIDEBAR("サイドバー（折りたたみ）"),
    CONTENT("コンテンツ先頭");

    companion object {
        fun fromPreference(value: String?): StartupFocusTarget =
            entries.firstOrNull { it.name == value } ?: SIDEBAR
    }
}
