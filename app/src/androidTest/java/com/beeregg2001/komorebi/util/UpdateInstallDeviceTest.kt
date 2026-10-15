package com.beeregg2001.komorebi.util

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.beeregg2001.komorebi.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

/** Opt-in: these cases operate the real system installer and may replace the running app. */
@RunWith(AndroidJUnit4::class)
class UpdateInstallDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(instrumentation)

    private fun launchUpdater(): AppUpdater {
        val context = instrumentation.targetContext
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var updater: AppUpdater? = null
        val deadline = System.currentTimeMillis() + 15_000
        while (updater == null && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                updater = activity?.appUpdater
            }
            if (updater == null) Thread.sleep(100)
        }
        return checkNotNull(updater) { "MainActivity did not resume" }
    }

    @Test fun installSelectedApkThroughActualUpdater() = runBlocking {
        val apkUrl = arguments.getString("updateApkUrl")
        val outcome = arguments.getString("installOutcome")
        assumeTrue("Pass updateApkUrl and installOutcome=cancel|approve explicitly",
            !apkUrl.isNullOrBlank() && outcome in listOf("cancel", "approve"))
        val updater = launchUpdater()
        updater.downloadAndInstallUpdate(apkUrl!!)
        val confirm = device.wait(Until.findObject(By.text(Pattern.compile(
            "^(更新|安裝|安装|インストール|Install|Update)$", Pattern.CASE_INSENSITIVE))), 30_000)
        if (confirm == null) {
            val hierarchy = ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            instrumentation.sendStatus(1, Bundle().apply {
                putString("stream", hierarchy.toString("UTF-8") + "\n")
            })
        }
        assertNotNull("System confirmation missing; state=${updater.updateState.value}", confirm)
        assertTrue("Correct app label must be visible", device.hasObject(By.text("Honorebi")))
        val sessionId = instrumentation.targetContext.getSharedPreferences("update_install", 0)
            .getInt("session_id", -1)
        assertTrue("Session id must be persisted", sessionId >= 0)
        assertEquals(UpdateState.Installing, updater.updateState.value)
        Log.i("UpdateInstallDeviceTest", "CONFIRMATION_VISIBLE session=$sessionId outcome=$outcome")
        instrumentation.sendStatus(1, Bundle().apply {
            putString("stream", "CONFIRMATION_VISIBLE session=$sessionId outcome=$outcome\n")
        })
        if (outcome == "cancel") {
            val cancel = device.findObject(By.text(Pattern.compile(
                "^(取消|キャンセル|Cancel)$", Pattern.CASE_INSENSITIVE)))
            assertNotNull("System cancel action missing", cancel)
            cancel.click()
            val deadline = System.currentTimeMillis() + 15_000
            while (updater.updateState.value !is UpdateState.Cancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            assertEquals(UpdateState.Cancelled, updater.updateState.value)
            assertEquals("cancelled", instrumentation.targetContext.getSharedPreferences("update_install", 0)
                .getString("result", null))
            assertFalse(instrumentation.targetContext.getSharedPreferences("update_install", 0).contains("session_id"))
        } else {
            // Self replacement normally terminates instrumentation. Verify final persisted success
            // in a separate invocation of completedSelfUpdateSurvivesRestart after replacement.
            confirm.click()
            val deadline = System.currentTimeMillis() + 90_000
            while (updater.updateState.value is UpdateState.Installing && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            assertEquals(UpdateState.UpToDate, updater.updateState.value)
        }
    }

    @Test fun completedSelfUpdateSurvivesRestart() {
        assumeTrue("Run only after an explicitly approved self-update",
            arguments.getString("verifySelfUpdate") == "true")
        val updater = launchUpdater()
        val preferences = instrumentation.targetContext.getSharedPreferences("update_install", 0)
        assertEquals("success", preferences.getString("result", null))
        assertFalse(preferences.contains("session_id"))
        assertFalse(preferences.contains("error"))
        assertEquals(UpdateState.UpToDate, updater.updateState.value)
    }
}
