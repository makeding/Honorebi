package com.beeregg2001.komorebi.data

import androidx.datastore.preferences.core.Preferences

enum class ConnectionSource(val backend: String, val label: String) {
    KONOMITV("KONOMITV", "KonomiTV"),
    EDCB("EDCB", "EDCB"),
    MIRAKURUN("MIRAKURUN_ONLY", "Mirakurun"),
}

/** The same configured-source rule owns the settings choices and the active backend. */
object ConnectionSourcePolicy {
    const val KONOMI_PLACEHOLDER = "https://192-168-xxx-xxx.local.konomi.tv"

    fun configured(konomiIp: String, konomiPort: String, edcbIp: String, edcbPort: String,
                   mirakurunIp: String, mirakurunPort: String): List<ConnectionSource> {
        fun ready(host: String, port: String) = host.isNotBlank() &&
            host.trim() != KONOMI_PLACEHOLDER && port.trim().toIntOrNull() in 1..65535
        return buildList {
            if (ready(konomiIp, konomiPort)) add(ConnectionSource.KONOMITV)
            if (ready(edcbIp, edcbPort)) add(ConnectionSource.EDCB)
            if (ready(mirakurunIp, mirakurunPort)) add(ConnectionSource.MIRAKURUN)
        }
    }

    fun resolve(selected: String?, configured: List<ConnectionSource>): String =
        configured.firstOrNull { it.backend == selected }?.backend
            ?: configured.firstOrNull()?.backend ?: selected ?: "KONOMITV"
}

internal fun Preferences.configuredConnectionSources(): List<ConnectionSource> =
    ConnectionSourcePolicy.configured(
        this[SettingsRepository.KONOMI_IP].orEmpty(), this[SettingsRepository.KONOMI_PORT] ?: "7000",
        this[SettingsRepository.EDCB_IP].orEmpty(), this[SettingsRepository.EDCB_PORT] ?: "4510",
        this[SettingsRepository.MIRAKURUN_IP].orEmpty(), this[SettingsRepository.MIRAKURUN_PORT] ?: "40772",
    )

internal fun Preferences.resolvedBackendType(): String =
    ConnectionSourcePolicy.resolve(this[SettingsRepository.BACKEND_TYPE], configuredConnectionSources())

data class ConnectionSourceSettings(
    val id: String,
    val label: String = "",
    val host: String,
    val port: String,
    val httpPort: String? = null,
)

data class ConnectionSettingsSnapshot(
    val sources: List<ConnectionSourceSettings>,
    val mainSystem: String,
    val scope: String? = null,
)

internal fun Preferences.connectionSettingsSnapshot(): ConnectionSettingsSnapshot = ConnectionSettingsSnapshot(
    sources = listOf(
        ConnectionSourceSettings("KONOMITV", "KonomiTV",
            this[SettingsRepository.KONOMI_IP].orEmpty().takeUnless { it == ConnectionSourcePolicy.KONOMI_PLACEHOLDER }.orEmpty(),
            this[SettingsRepository.KONOMI_PORT] ?: "7000"),
        ConnectionSourceSettings("EDCB", "EDCB", this[SettingsRepository.EDCB_IP].orEmpty(),
            this[SettingsRepository.EDCB_PORT] ?: "4510", this[SettingsRepository.EDCB_HTTP_PORT] ?: "5510"),
        ConnectionSourceSettings("MIRAKURUN_ONLY", "Mirakurun", this[SettingsRepository.MIRAKURUN_IP].orEmpty(),
            this[SettingsRepository.MIRAKURUN_PORT] ?: "40772"),
    ), mainSystem = resolvedBackendType(),
)
