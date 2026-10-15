package com.beeregg2001.komorebi.data.repository

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34])
class LauncherAppUpdatesTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = shadowOf(context.packageManager)
    private val launcherIntent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

    private fun install(name: String, version: Int = 1) {
        val appPackage = "tv.example.$name"
        manager.removeResolveInfosForIntent(launcherIntent, appPackage)
        manager.addResolveInfoForIntent(launcherIntent, ResolveInfo().apply {
            nonLocalizedLabel = "$name $version"
            activityInfo = ActivityInfo().apply {
                packageName = appPackage
                this.name = "$packageName.MainActivity"
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = appPackage
                    sourceDir = "/data/app/$name-$version/base.apk"
                }
            }
        })
    }

    private fun broadcast(action: String, name: String, replacing: Boolean = false) {
        context.sendBroadcast(Intent(action, Uri.parse("package:tv.example.$name"))
            .putExtra(Intent.EXTRA_REPLACING, replacing))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun installsAndRemovalsRefreshWithoutAnAppsTab() = runBlocking {
        install("First")
        val refreshes = Channel<Unit>(Channel.CONFLATED)
        val results = Channel<List<String>>(Channel.UNLIMITED)
        val job = launch {
            LauncherAppRepository(context).observeLauncherApps(refreshes.receiveAsFlow())
                .collect { results.send(it.map { app -> app.label }) }
        }
        try {
            assertEquals(listOf("First 1"), withTimeout(5_000) { results.receive() })
            install("Second")
            broadcast(Intent.ACTION_PACKAGE_ADDED, "Second")
            assertEquals(listOf("First 1", "Second 1"), withTimeout(5_000) { results.receive() })
            manager.removeResolveInfosForIntent(launcherIntent, "tv.example.First")
            broadcast(Intent.ACTION_PACKAGE_REMOVED, "First")
            assertEquals(listOf("Second 1"), withTimeout(5_000) { results.receive() })
        } finally { job.cancelAndJoin() }
    }

    @Test fun replacementKeepsCardUntilNewMetadataAndComponentChangesAreRead() = runBlocking {
        install("First")
        val refreshes = Channel<Unit>(Channel.CONFLATED)
        val results = Channel<List<String>>(Channel.UNLIMITED)
        val job = launch {
            LauncherAppRepository(context).observeLauncherApps(refreshes.receiveAsFlow())
                .collect { results.send(it.map { app -> app.label }) }
        }
        try {
            assertEquals(listOf("First 1"), withTimeout(5_000) { results.receive() })
            manager.removeResolveInfosForIntent(launcherIntent, "tv.example.First")
            broadcast(Intent.ACTION_PACKAGE_REMOVED, "First", replacing = true)
            assertNull(withTimeoutOrNull(200) { results.receive() })
            install("First", version = 2)
            broadcast(Intent.ACTION_PACKAGE_REPLACED, "First", replacing = true)
            assertEquals(listOf("First 2"), withTimeout(5_000) { results.receive() })
            manager.removeResolveInfosForIntent(launcherIntent, "tv.example.First")
            broadcast(Intent.ACTION_PACKAGE_CHANGED, "First")
            assertEquals(emptyList<String>(), withTimeout(5_000) { results.receive() })
        } finally { job.cancelAndJoin() }
    }

    @Test fun foregroundRefreshRecoversMissedEventsAndCancellationUnregistersReceiver() = runBlocking {
        install("First")
        val baseline = shadowOf(context).registeredReceivers.size
        val refreshes = Channel<Unit>(Channel.CONFLATED)
        val results = Channel<List<String>>(Channel.UNLIMITED)
        val repository = LauncherAppRepository(context)
        val job = launch {
            repository.observeLauncherApps(refreshes.receiveAsFlow())
                .collect { results.send(it.map { app -> app.label }) }
        }
        try {
            assertEquals(listOf("First 1"), withTimeout(5_000) { results.receive() })
            assertEquals(baseline + 1, shadowOf(context).registeredReceivers.size)
            install("Second") // Simulate a missed system broadcast.
            refreshes.send(Unit)
            assertEquals(listOf("First 1", "Second 1"), withTimeout(5_000) { results.receive() })
        } finally { job.cancelAndJoin() }
        assertEquals(baseline, shadowOf(context).registeredReceivers.size)
    }
}
