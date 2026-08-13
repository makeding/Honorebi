package com.beeregg2001.komorebi.data.model

enum class CmSkipMode {
    OFF,
    MANUAL,
    AUTO;

    fun next(): CmSkipMode = when (this) {
        OFF -> MANUAL
        MANUAL -> AUTO
        AUTO -> OFF
    }

    companion object {
        fun fromPreference(value: String?): CmSkipMode = when (value) {
            "ON", "AUTO" -> AUTO
            "MANUAL" -> MANUAL
            else -> OFF
        }
    }
}
