package com.beeregg2001.komorebi.data.repository

import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.beeregg2001.komorebi.data.model.LauncherApp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LauncherAppRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val packageManager = context.packageManager
    private val launcherCachePreferences =
        context.getSharedPreferences(LAUNCHER_CACHE_PREFERENCES, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun observeLauncherApps(refreshRequests: Flow<Unit>): Flow<List<LauncherApp>> =
        merge(packageChanges(), refreshRequests)
            .conflate()
            .mapNotNull {
                try {
                    loadLauncherApps()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Retain the last complete catalog and keep observing later events.
                    Log.w("LauncherAppRepository", "Failed to refresh launcher apps", e)
                    null
                }
            }
            .distinctUntilChanged()

    private fun packageChanges(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.data?.scheme != "package" ||
                    intent.data?.schemeSpecificPart.isNullOrBlank()
                ) return
                // An update removes the old APK before adding its replacement.
                // Keep its card until the replacement is available to query.
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                    intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                ) return
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        // Subscribe before scanning so an install during the scan is not lost.
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    suspend fun loadLauncherApps(): List<LauncherApp> = withContext(Dispatchers.IO) {
        val cachedApps = readCachedLauncherApps()
        val cachedByStableId = cachedApps.associateBy { "${it.packageName}/${it.activityName}" }
        val leanbackApps = queryMainActivities(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val normalApps = queryMainActivities(Intent.CATEGORY_LAUNCHER)

        val resolvedApps = (leanbackApps + normalApps)
            .asSequence()
            .filter { it.activityInfo?.packageName != null && it.activityInfo?.name != null }
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .map { info ->
                val activityInfo = info.activityInfo
                val applicationInfo = activityInfo.applicationInfo
                val resourcePackage = info.resolvePackageName ?: activityInfo.packageName
                val iconResourceId = firstLauncherResourceId(
                    info.iconResource,
                    activityInfo.icon,
                    applicationInfo.icon,
                )
                val bannerResourceId = firstLauncherResourceId(
                    activityInfo.banner,
                    applicationInfo.banner,
                )
                val iconUri = launcherResourceUri(resourcePackage, iconResourceId)
                val bannerUri = launcherResourceUri(resourcePackage, bannerResourceId)
                val artworkCacheVersion = applicationInfo.sourceDir.orEmpty()
                val stableId = "${activityInfo.packageName}/${activityInfo.name}"
                val cached = cachedByStableId[stableId]

                if (cached != null &&
                    cached.icon == iconUri &&
                    cached.banner == bannerUri &&
                    cached.artworkCacheVersion == artworkCacheVersion
                ) {
                    cached
                } else LauncherApp(
                    packageName = activityInfo.packageName,
                    activityName = activityInfo.name,
                    label = info.loadLabel(packageManager)?.toString().orEmpty()
                        .ifBlank { activityInfo.packageName },
                    icon = iconUri,
                    banner = bannerUri,
                    artworkCacheVersion = artworkCacheVersion,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()

        if (resolvedApps != cachedApps) {
            launcherCachePreferences.edit()
                .putString(launcherCacheKey(), gson.toJson(resolvedApps))
                .apply()
        }
        resolvedApps
    }

    private fun readCachedLauncherApps(): List<LauncherApp> = runCatching {
        val json = launcherCachePreferences.getString(launcherCacheKey(), null)
            ?: return@runCatching emptyList()
        gson.fromJson<List<LauncherApp>>(json, LAUNCHER_APP_LIST_TYPE) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun launcherCacheKey(): String =
        "launcher_apps_v1_${Locale.getDefault().toLanguageTag()}"

    fun launch(app: LauncherApp): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            val fallback = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
                true
            } else {
                false
            }
        }
    }

    fun launchInputSourcePicker(fallbackApp: LauncherApp?): Boolean {
        val inputIntent = Intent(ACTION_VIEW_INPUTS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(LIVE_TV_PACKAGE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        if (tryStartActivity(inputIntent)) return true

        val explicitInputIntent = Intent(ACTION_VIEW_INPUTS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            component = ComponentName(LIVE_TV_PACKAGE, LIVE_TV_INPUT_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        if (tryStartActivity(explicitInputIntent)) return true

        return fallbackApp?.let { launch(it) } ?: false
    }

    fun launchSystemSettings(): Boolean {
        val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        if (tryStartActivity(settingsIntent)) return true

        SETTINGS_PACKAGES.forEach { packageName ->
            val fallback = packageManager.getLaunchIntentForPackage(packageName)
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                if (tryStartActivity(fallback)) return true
            }
        }

        return false
    }

    fun launchWifiSettings(): Boolean {
        val wifiSettingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        if (tryStartActivity(wifiSettingsIntent)) return true

        return launchSystemSettings()
    }

    fun launchAppDetails(app: LauncherApp): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${app.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        return tryStartActivity(intent)
    }

    private fun queryMainActivities(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private companion object {
        const val LAUNCHER_CACHE_PREFERENCES = "launcher_app_metadata_cache"
        const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"
        const val LIVE_TV_PACKAGE = "com.mitv.livetv"
        const val LIVE_TV_INPUT_ACTIVITY = "com.mitv.livetv.input.SelectInputActivity"
        val SETTINGS_PACKAGES = listOf(
            "com.android.tv.settings",
            "com.android.settings",
            "com.xiaomi.mitv.settings"
        )
        val LAUNCHER_APP_LIST_TYPE = object : TypeToken<List<LauncherApp>>() {}.type
    }
}

internal fun firstLauncherResourceId(vararg candidates: Int): Int =
    candidates.firstOrNull { it != 0 } ?: 0

internal fun launcherResourceUri(packageName: String, resourceId: Int): String? =
    if (packageName.isBlank() || resourceId == 0) null
    else "android.resource://$packageName/$resourceId"
