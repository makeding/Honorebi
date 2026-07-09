package com.beeregg2001.komorebi.data.model

import android.graphics.drawable.Drawable

data class LauncherApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable,
    val banner: Drawable?
) {
    val stableId: String = "$packageName/$activityName"
}
