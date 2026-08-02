package com.beeregg2001.komorebi

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps the Google TV launcher package available to system components without
 * allowing its Home activity to replace Honorebi.
 */
class LauncherGuardAccessibilityService : AccessibilityService() {

    private var lastWindowRedirectAt = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return super.onKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "Intercepted HOME key before launcher")
            redirectToHonorebi()
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != GOOGLE_TV_LAUNCHER_PACKAGE) return

        val className = event.className?.toString() ?: return
        if (className !in GOOGLE_TV_HOME_ACTIVITIES) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastWindowRedirectAt < REDIRECT_COOLDOWN_MS) return
        lastWindowRedirectAt = now

        Log.i(TAG, "Redirecting Google TV Home window")
        redirectToHonorebi()
    }

    private fun redirectToHonorebi() {
        val honorebiHome = Intent(Intent.ACTION_MAIN).apply {
            setClass(this@LauncherGuardAccessibilityService, MainActivity::class.java)
            addCategory(Intent.CATEGORY_HOME)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        runCatching { startActivity(honorebiHome) }
            .onFailure { error -> Log.e(TAG, "Failed to redirect Google TV Home", error) }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "HonorebiLauncherGuard"
        const val GOOGLE_TV_LAUNCHER_PACKAGE = "com.google.android.apps.tv.launcherx"
        const val REDIRECT_COOLDOWN_MS = 1_000L

        val GOOGLE_TV_HOME_ACTIVITIES = setOf(
            "com.google.android.apps.tv.launcherx.home.HomeActivity",
            "com.google.android.apps.tv.launcherx.home.VanillaModeHomeActivity"
        )
    }
}
