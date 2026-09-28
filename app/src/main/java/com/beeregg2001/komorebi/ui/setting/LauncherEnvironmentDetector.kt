package com.beeregg2001.komorebi.ui.setting

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

enum class HomeEnvironment {
    ANDROID_TV,
    GOOGLE_TV,
    UNKNOWN
}

/**
 * Detects the launcher environment so the settings screen can guide the user through becoming the
 * default Home app. Android TV exposes a system Home-settings screen, while Google TV locks the
 * default launcher behind its own Android TV Core Services, which requires a third-party tool.
 */
object LauncherEnvironmentDetector {
    private const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
    private const val ANDROID_TV_LAUNCHER = "com.google.android.leanbacklauncher"
    private const val CHROMECAST_SHELL = "com.google.android.apps.mediashell"

    const val TV_TWEAKS_URL = "https://tvtweaks.huggy.moe"

    fun detect(context: Context): HomeEnvironment {
        val pm = context.packageManager
        return classify(
            hasGoogleTvLauncher = isPackageInstalled(pm, GOOGLE_TV_LAUNCHER),
            hasAndroidTvLauncher = isPackageInstalled(pm, ANDROID_TV_LAUNCHER),
            hasChromecastShell = isPackageInstalled(pm, CHROMECAST_SHELL),
            isLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
        )
    }

    internal fun classify(
        hasGoogleTvLauncher: Boolean,
        hasAndroidTvLauncher: Boolean,
        hasChromecastShell: Boolean,
        isLeanback: Boolean,
        manufacturer: String,
        model: String,
    ): HomeEnvironment = when {
        hasGoogleTvLauncher -> HomeEnvironment.GOOGLE_TV
        hasChromecastShell -> HomeEnvironment.GOOGLE_TV
        manufacturer.equals("Google", ignoreCase = true) && model.contains("Chromecast", ignoreCase = true) ->
            HomeEnvironment.GOOGLE_TV
        hasAndroidTvLauncher || isLeanback -> HomeEnvironment.ANDROID_TV
        else -> HomeEnvironment.UNKNOWN
    }

    fun isDefaultHome(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /** Opens the system Home-app picker. Returns false when the OEM hides/removes the screen. */
    fun openHomeSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)
        if (!resolvable) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean =
        runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess
}
