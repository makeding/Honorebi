package com.beeregg2001.komorebi.data.model

data class LauncherApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    /** Android resource URI; decoded lazily by Coil instead of PackageManager during scan. */
    val icon: String?,
    val banner: String?,
    /** Changes when Android installs a replacement APK, invalidating Coil artwork keys. */
    val artworkCacheVersion: String,
) {
    val stableId: String = "$packageName/$activityName"
}
