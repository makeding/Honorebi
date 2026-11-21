package com.beeregg2001.komorebi.data

enum class PlayerClockMode(val label: String) {
    ALWAYS_ON("常に表示"),
    ONLY_NEWS("ニュースのみ"),
    ALWAYS_OFF("表示しない");

    fun showsCurrentTime(majorGenres: List<String>?): Boolean = when (this) {
        ALWAYS_ON -> true
        ONLY_NEWS -> majorGenres?.any { it == "ニュース・報道" } == true
        ALWAYS_OFF -> false
    }

    companion object {
        fun fromPreference(value: String?): PlayerClockMode =
            entries.firstOrNull { it.name == value } ?: ALWAYS_ON
    }
}
