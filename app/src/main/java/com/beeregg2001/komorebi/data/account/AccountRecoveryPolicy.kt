package com.beeregg2001.komorebi.data.account

import com.beeregg2001.komorebi.data.ConnectionSourcePolicy

data class AccountRecoverySummary(
    val userName: String? = null,
    val connectionLabels: List<String> = emptyList(),
) {
    val hasRecoverableData: Boolean
        get() = !userName.isNullOrBlank() || connectionLabels.isNotEmpty()
}

internal fun shouldPromptAccountRecovery(
    hasConfiguredSettingsInStore: Boolean,
    record: KomorebiAccount.Record?,
): Boolean {
    if (hasConfiguredSettingsInStore || record == null) return false
    return record.settings.isNotEmpty() || record.session != null
}

internal fun summarizeRecoverableAccount(record: KomorebiAccount.Record?): AccountRecoverySummary {
    if (record == null) return AccountRecoverySummary()
    return AccountRecoverySummary(
        userName = record.session?.userName?.takeIf { it.isNotBlank() },
        connectionLabels = buildRecoverableConnectionLabels(record.settings),
    )
}

internal fun buildRecoverableConnectionLabels(settings: Map<String, String>): List<String> {
    if (settings.isEmpty()) return emptyList()
    val labels = mutableListOf<String>()
    if (settings["backend_type"] == "TIF") {
        labels += "本体のテレビ入力 (TIF)"
    }
    val konomiIp = settings["konomi_ip"]?.trim().orEmpty()
    if (konomiIp.isNotEmpty() && konomiIp != ConnectionSourcePolicy.KONOMI_PLACEHOLDER) {
        val port = settings["konomi_port"]?.trim()?.ifEmpty { null } ?: "7000"
        labels += "KonomiTV ($konomiIp:$port)"
    }
    val edcbIp = settings["edcb_ip"]?.trim().orEmpty()
    if (edcbIp.isNotEmpty()) {
        val port = settings["edcb_port"]?.trim()?.ifEmpty { null } ?: "4510"
        labels += "EDCB ($edcbIp:$port)"
    }
    val mirakurunIp = settings["mirakurun_ip"]?.trim().orEmpty()
    if (mirakurunIp.isNotEmpty()) {
        val port = settings["mirakurun_port"]?.trim()?.ifEmpty { null } ?: "40772"
        labels += "Mirakurun ($mirakurunIp:$port)"
    }
    val epgStationIp = settings["epgstation_ip"]?.trim().orEmpty()
    if (epgStationIp.isNotEmpty()) {
        val port = settings["epgstation_port"]?.trim()?.ifEmpty { null } ?: "8888"
        labels += "EPGStation ($epgStationIp:$port)"
    }
    val smbRaw = settings["smb_server_list"]?.trim().orEmpty()
    if (smbRaw.isNotEmpty() && smbRaw != "[]") {
        labels += "SMB サーバー設定"
    }
    return labels
}
