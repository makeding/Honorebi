package com.beeregg2001.komorebi.util

/** Select only explicitly published assets, in the operating system's ABI preference order. */
internal object UpdateApkSelector {
    fun select(supportedAbis: List<String>, abiUrls: Map<String, String>, universalUrl: String): String =
        supportedAbis.firstNotNullOfOrNull { abi -> abiUrls[abi]?.takeIf { it.isNotBlank() } }
            ?: universalUrl
}
