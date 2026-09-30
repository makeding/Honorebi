package com.beeregg2001.komorebi.data.model

import java.text.Normalizer
import java.util.Locale

enum class NHKExclusionMode(val displayLabel: String) {
    ON("ON"),
    TEMPORARY("一時退避（30分）"),
    OFF("OFF");

    companion object {
        fun fromPreference(value: String?): NHKExclusionMode =
            entries.firstOrNull { it.name == value } ?: OFF
    }
}

data class NHKExclusionState(
    val mode: NHKExclusionMode = NHKExclusionMode.OFF,
    val expiresAtMillis: Long? = null,
    val isLoaded: Boolean = false,
    val channelRevision: Long = 0,
) {
    val isActive: Boolean get() = isLoaded && mode != NHKExclusionMode.OFF

    fun atTime(nowMillis: Long): NHKExclusionState =
        if (mode == NHKExclusionMode.TEMPORARY && (expiresAtMillis == null || expiresAtMillis <= nowMillis)) {
            copy(mode = NHKExclusionMode.OFF, expiresAtMillis = null)
        } else this

    fun temporarilyEnabled(nowMillis: Long): NHKExclusionState =
        if (mode == NHKExclusionMode.ON) this else copy(
            mode = NHKExclusionMode.TEMPORARY,
            expiresAtMillis = nowMillis + TEMPORARY_NHK_HIDE_MILLIS,
            isLoaded = true,
        )
}

const val TEMPORARY_NHK_HIDE_MILLIS = 30 * 60 * 1_000L

object NHKChannelClassifier {
    fun isNHKChannel(name: String?): Boolean {
        val normalized = Normalizer.normalize(name.orEmpty(), Normalizer.Form.NFKC)
            .uppercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return normalized.startsWith("NHK") || normalized.startsWith("Eテレ") ||
            normalized.startsWith("日本放送協会")
    }
}
