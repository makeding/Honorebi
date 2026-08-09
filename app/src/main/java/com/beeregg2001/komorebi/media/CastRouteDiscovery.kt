package com.beeregg2001.komorebi.media

import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executors

/**
 * Keeps the system's [MediaRouter2] provider for the on-device Cast receiver
 * (com.google.android.apps.mediashell) bound while Honorebi is playing.
 *
 * On Android TV firmware that uses Google's MediaShell as the Cast/Chromecast
 * receiver, the system only binds the receiver's MediaRoute2ProviderService
 * while at least one foreground app holds an active RouteDiscoveryPreference
 * with non-empty preferred features. After the screen sleeps or the last
 * scanning app backgrounds, the provider is unbound and the TV stops
 * advertising itself as a Cast target, so phones can no longer discover or
 * hand off playback to it.
 *
 * Registering a preference here while a playback screen is active keeps the
 * provider bound for the lifetime of that screen, restoring the link between
 * Honorebi's MediaSession and the Cast output switcher without requiring a
 * reboot or manual launcher interaction.
 */
@Composable
fun CastRouteDiscovery() {
    val context = LocalContext.current.applicationContext
    val router = remember { runCatching { MediaRouter2.getInstance(context) }.getOrNull() } ?: return

    DisposableEffect(router) {
        val callback = object : MediaRouter2.RouteCallback() {}
        val preference = RouteDiscoveryPreference.Builder(
            listOf(
                MediaRoute2Info.FEATURE_REMOTE_PLAYBACK,
                MediaRoute2Info.FEATURE_LIVE_AUDIO,
                MediaRoute2Info.FEATURE_LIVE_VIDEO,
            ),
            /* activeScan = */ true,
        ).build()
        val executor = Executors.newSingleThreadExecutor()
        runCatching {
            router.registerRouteCallback(executor, callback, preference)
        }.onFailure { Log.w(TAG, "registerRouteCallback failed", it) }

        onDispose {
            runCatching { router.unregisterRouteCallback(callback) }.onFailure {
                Log.w(TAG, "unregisterRouteCallback failed", it)
            }
            executor.shutdown()
        }
    }
}

private const val TAG = "CastRouteDiscovery"