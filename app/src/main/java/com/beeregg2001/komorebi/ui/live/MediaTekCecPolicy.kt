package com.beeregg2001.komorebi.ui.live

internal fun usesMediaTekCec(servicePackage: String?, hdmi: Boolean, parentId: String?): Boolean =
    servicePackage == "com.mediatek.tis" && hdmi && parentId != null
