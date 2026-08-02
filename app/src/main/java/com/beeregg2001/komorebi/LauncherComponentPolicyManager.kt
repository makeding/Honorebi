package com.beeregg2001.komorebi

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Uses the Device Policy Management role permission to block LauncherX's HOME
 * entry points and its account-verification interstitial. The package and its
 * Cast-facing providers stay enabled.
 */
object LauncherComponentPolicyManager {

    private const val TAG = "HonorebiLauncherPolicy"
    private const val CHANGE_COMPONENT_PERMISSION =
        "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
    private const val LAUNCHER_PACKAGE = "com.google.android.apps.tv.launcherx"

    private val blockedLauncherClasses = listOf(
        "com.google.android.apps.tv.launcherx.home.HomeActivity",
        "com.google.android.apps.tv.launcherx.home.VanillaModeHomeActivity",
        "com.google.android.apps.tv.launcherx.profile.core.AccountVerificationActivity"
    )

    fun disableHomeActivitiesIfAuthorized(context: Context): Boolean =
        setHomeActivitiesState(
            context,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            "disabled"
        )

    fun restoreHomeActivitiesIfAuthorized(context: Context): Boolean =
        setHomeActivitiesState(
            context,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            "restored to manifest defaults"
        )

    private fun setHomeActivitiesState(
        context: Context,
        state: Int,
        stateDescription: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(CHANGE_COMPONENT_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Device Policy Management component permission is not granted")
            return false
        }

        val settings = blockedLauncherClasses.map { className ->
            PackageManager.ComponentEnabledSetting(
                ComponentName(LAUNCHER_PACKAGE, className),
                state,
                PackageManager.DONT_KILL_APP
            )
        }

        return runCatching {
            context.packageManager.setComponentEnabledSettings(settings)
        }.onSuccess {
            Log.i(TAG, "LauncherX UI entry points $stateDescription; package remains enabled")
        }.onFailure { error ->
            Log.e(TAG, "Failed to update LauncherX HOME activity state", error)
        }.isSuccess
    }
}
