package com.beeregg2001.komorebi.data.api

/** Pins both session creation and deletion to the same configured backend. */
data class LiveSessionBackendTarget(val baseUrl: String)

/** Pins history writes to the session that was active when playback observed media. */
data class HistorySyncBackendTarget(val baseUrl: String, val authorization: String)
