package com.beeregg2001.komorebi.data.remote

enum class CastAccessMode(val label: String, val account: Boolean, val lan: Boolean) {
    ACCOUNT("Account", true, false),
    LAN("Account + LAN", true, true),
    OFF("OFF", false, false),
}
