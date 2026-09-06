package com.beeregg2001.komorebi.ui.player

/** Channel-number prefix shared by live and recorded presentation. */
fun formatChannelType(type: String): String = when (type.uppercase()) {
    "GR" -> "地デジ"
    "BS" -> "BS"
    "CS" -> "CS"
    else -> type
}
