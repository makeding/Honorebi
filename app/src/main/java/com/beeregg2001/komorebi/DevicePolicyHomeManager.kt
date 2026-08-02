package com.beeregg2001.komorebi

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Owns the Device Owner / Android 14 Device Policy Management role based HOME override.
 *
 * LauncherX must remain enabled because its providers are used by MediaShell.
 * This policy changes only HOME intent resolution; it does not disable, suspend,
 * or kill the Google TV launcher package.
 */
object DevicePolicyHomeManager {

    const val ACTION_CLEAR_HOME_POLICY =
        "com.beeregg2001.Honorebi.action.CLEAR_DEVICE_POLICY_HOME"

    private const val TAG = "HonorebiDevicePolicy"
    private const val MANAGE_LOCK_TASK_PERMISSION =
        "android.permission.MANAGE_DEVICE_POLICY_LOCK_TASK"

    fun applyIfAuthorized(context: Context): Boolean {
        val admin = authorizedAdmin(context)
        if (admin == null && !hasRolePermission(context)) {
            Log.i(TAG, "Device policy authorization is not granted; leaving HOME unchanged")
            return false
        }

        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val homeActivity = ComponentName(context, MainActivity::class.java)

        return runCatching {
            devicePolicyManager(context).addPersistentPreferredActivity(
                admin,
                homeFilter,
                homeActivity
            )
        }.onSuccess {
            Log.i(TAG, "Persistent HOME policy applied to $homeActivity")
        }.onFailure { error ->
            Log.e(TAG, "Failed to apply persistent HOME policy", error)
        }.isSuccess
    }

    fun clearIfAuthorized(context: Context): Boolean {
        val admin = authorizedAdmin(context)
        if (admin == null && !hasRolePermission(context)) {
            Log.e(TAG, "Cannot clear HOME policy without device policy authorization")
            return false
        }

        return runCatching {
            devicePolicyManager(context).clearPackagePersistentPreferredActivities(
                admin,
                context.packageName
            )
        }.onSuccess {
            Log.i(TAG, "Persistent HOME policy cleared for ${context.packageName}")
        }.onFailure { error ->
            Log.e(TAG, "Failed to clear persistent HOME policy", error)
        }.isSuccess
    }

    private fun authorizedAdmin(context: Context): ComponentName? =
        ComponentName(context, HonorebiDeviceAdminReceiver::class.java).takeIf {
            devicePolicyManager(context).isDeviceOwnerApp(context.packageName)
        }

    private fun hasRolePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.checkSelfPermission(MANAGE_LOCK_TASK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun devicePolicyManager(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)
}
