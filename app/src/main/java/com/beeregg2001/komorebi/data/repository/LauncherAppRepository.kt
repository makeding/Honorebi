package com.beeregg2001.komorebi.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.beeregg2001.komorebi.data.model.LauncherApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LauncherAppRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val packageManager = context.packageManager

    suspend fun loadLauncherApps(): List<LauncherApp> = withContext(Dispatchers.IO) {
        val leanbackApps = queryMainActivities(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val normalApps = queryMainActivities(Intent.CATEGORY_LAUNCHER)

        (leanbackApps + normalApps)
            .asSequence()
            .filter { it.activityInfo?.packageName != null && it.activityInfo?.name != null }
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .map { info ->
                LauncherApp(
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    label = info.loadLabel(packageManager)?.toString().orEmpty()
                        .ifBlank { info.activityInfo.packageName },
                    icon = info.loadIcon(packageManager),
                    banner = loadBanner(info)
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

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

    private fun loadBanner(info: ResolveInfo) =
        runCatching {
            packageManager.getActivityBanner(
                ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            )
        }.getOrNull()
            ?: runCatching { packageManager.getApplicationBanner(info.activityInfo.packageName) }
                .getOrNull()

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
        const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"
        const val LIVE_TV_PACKAGE = "com.mitv.livetv"
        const val LIVE_TV_INPUT_ACTIVITY = "com.mitv.livetv.input.SelectInputActivity"
    }
}
