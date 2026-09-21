package com.beeregg2001.komorebi.data.api

/** Pins both session creation and deletion to the same configured backend. */
data class LiveSessionBackendTarget(val baseUrl: String)
