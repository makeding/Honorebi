package com.beeregg2001.komorebi.data

object TifIntegrationPolicy {
    /** TIF as main system can never be switched off (it is the app's only source then). */
    fun canDisable(backendType: String): Boolean = backendType != "TIF"

    fun isActive(backendType: String, enabled: Boolean, supported: Boolean): Boolean =
        backendType == "TIF" || (supported && enabled)
}
