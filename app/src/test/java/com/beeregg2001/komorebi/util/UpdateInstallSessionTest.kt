package com.beeregg2001.komorebi.util

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowPackageInstaller
import org.robolectric.shadow.api.Shadow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34])
class UpdateInstallSessionTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val preferences = context.getSharedPreferences("update_install", 0)

    private fun session(): Int = context.packageManager.packageInstaller.createSession(
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    ).also { preferences.edit().putInt("session_id", it).commit() }

    private fun result(id: Int, status: Int) = Intent(AppUpdater.INSTALL_RESULT_ACTION)
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)

    @Test fun confirmationSurvivesRecreationAndLaunchesOnlyFromResumedActivity() {
        val id = session()
        val updater = AppUpdater(context)
        updater.handleInstallResult(result(id, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent("test.INSTALL_CONFIRMATION")))
        assertTrue(updater.updateState.value is UpdateState.AwaitingInstallConfirmation)
        assertNull(shadowOf(context).nextStartedActivity)
        val restored = AppUpdater(context)
        assertTrue(restored.updateState.value is UpdateState.AwaitingInstallConfirmation)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        restored.launchInstallConfirmation(activity)
        assertEquals("test.INSTALL_CONFIRMATION", shadowOf(activity).nextStartedActivity.action)
        assertEquals(UpdateState.Installing, restored.updateState.value)
        assertFalse(preferences.contains("confirmation"))
        restored.launchInstallConfirmation(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun missingConfirmationAbandonsSessionAndRetainsErrorForRetry() {
        val id = session()
        val updater = AppUpdater(context)
        updater.handleInstallResult(result(id, PackageInstaller.STATUS_PENDING_USER_ACTION))
        val error = updater.updateState.value as UpdateState.Error
        assertTrue(error.message.contains("INSTALL_CONFIRMATION"))
        assertTrue(error.message.contains("更新を再確認"))
        assertNull(context.packageManager.packageInstaller.getSessionInfo(id))
        assertEquals(error, AppUpdater(context).updateState.value)
    }

    @Test fun staleCallbacksCannotOverrideCurrentSessionAndCancellationIsNotSuccess() {
        val id = session()
        val updater = AppUpdater(context)
        updater.handleInstallResult(result(id + 1, PackageInstaller.STATUS_SUCCESS))
        assertEquals(UpdateState.Installing, updater.updateState.value)
        assertEquals(id, preferences.getInt("session_id", -1))
        updater.handleInstallResult(result(id, PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(UpdateState.Cancelled, updater.updateState.value)
        assertEquals(UpdateState.Cancelled, AppUpdater(context).updateState.value)
        assertFalse(preferences.contains("session_id"))
        updater.handleInstallResult(result(id, PackageInstaller.STATUS_SUCCESS))
        assertEquals(UpdateState.Cancelled, updater.updateState.value)
    }

    @Test fun storageFailureRetainsReasonAndStatusAcrossRecreation() {
        val id = session()
        val updater = AppUpdater(context)
        updater.handleInstallResult(result(id, PackageInstaller.STATUS_FAILURE_STORAGE))
        val error = updater.updateState.value as UpdateState.Error
        assertTrue(error.message.contains("空き容量"))
        assertTrue(error.message.contains("INSTALL_STATUS_${PackageInstaller.STATUS_FAILURE_STORAGE}"))
        assertEquals(error, AppUpdater(context).updateState.value)
        updater.resetState()
        assertEquals(UpdateState.Idle, AppUpdater(context).updateState.value)
    }

    @Test fun onlyAndroidSuccessMarksUpdateComplete() {
        val id = session()
        val updater = AppUpdater(context)
        updater.resetState()
        assertEquals(UpdateState.Installing, updater.updateState.value)
        updater.handleInstallResult(result(id, PackageInstaller.STATUS_SUCCESS))
        assertEquals(UpdateState.UpToDate, updater.updateState.value)
        assertEquals(UpdateState.UpToDate, AppUpdater(context).updateState.value)
    }

    @Test fun vanishedSessionWithoutVersionChangeDoesNotReportSuccess() {
        val id = session()
        context.packageManager.packageInstaller.abandonSession(id)
        val updater = AppUpdater(context)
        assertTrue((updater.updateState.value as UpdateState.Error).message.contains("INSTALL_INTERRUPTED"))
        updater.handleInstallResult(result(id, PackageInstaller.STATUS_SUCCESS))
        assertEquals(UpdateState.UpToDate, updater.updateState.value)
    }

    @Test
    @Config(shadows = [RecordingSession::class])
    fun downloadStreamsIntoNamedSessionAndDoesNotTreatCommitAsSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("APK content", 3))
            val updater = AppUpdater(context)
            updater.downloadAndInstallUpdate(server.url("/update.apk").toString())
            val info = context.packageManager.packageInstaller.mySessions.single()
            assertEquals(context.packageName, info.appPackageName)
            assertEquals(context.applicationInfo.loadLabel(context.packageManager).toString(), info.appLabel.toString())
            assertNotNull(info.appIcon)
            val session = context.packageManager.packageInstaller.openSession(info.sessionId)
            val recorded: RecordingSession = Shadow.extract(session)
            assertEquals("base.apk", recorded.filename)
            assertEquals("APK content", recorded.bytes.toString("UTF-8"))
            assertTrue(recorded.synced)
            assertTrue(recorded.closed)
            assertNotNull(recorded.callback)
            assertEquals(UpdateState.ReadyToInstall, updater.updateState.value)
            assertEquals(info.sessionId, preferences.getInt("session_id", -1))
            assertNull(shadowOf(context).nextStartedActivity)
            // An active install must not create another session or another download.
            updater.downloadAndInstallUpdate(server.url("/duplicate.apk").toString())
            assertEquals(1, server.requestCount)
            assertEquals(1, context.packageManager.packageInstaller.mySessions.size)
        }
    }

    @Test
    @Config(shadows = [RecordingSession::class])
    fun interruptedDownloadAbandonsSessionAndAllowsRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(32_000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val updater = AppUpdater(context)
            updater.downloadAndInstallUpdate(server.url("/broken.apk").toString())
            assertTrue(updater.updateState.value is UpdateState.Error)
            assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
            assertFalse(preferences.contains("session_id"))
            server.enqueue(MockResponse().setBody("complete APK"))
            updater.downloadAndInstallUpdate(server.url("/retry.apk").toString())
            assertEquals(UpdateState.ReadyToInstall, updater.updateState.value)
            assertEquals(1, context.packageManager.packageInstaller.mySessions.size)
        }
    }
}

/** Record the actual stream and commit boundary; Android's final result is supplied separately. */
@Implements(PackageInstaller.Session::class)
class RecordingSession : ShadowPackageInstaller.ShadowSession() {
    val bytes = ByteArrayOutputStream()
    var filename: String? = null
    var synced = false
    var closed = false
    var callback: IntentSender? = null

    @Implementation
    public override fun openWrite(name: String, offsetBytes: Long, lengthBytes: Long): OutputStream {
        filename = name
        return object : OutputStream() {
            override fun write(value: Int) { bytes.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length) }
            override fun close() { closed = true }
        }
    }

    @Implementation
    public override fun fsync(out: OutputStream) { synced = true }

    @Implementation
    public override fun commit(statusReceiver: IntentSender) {
        check(closed && synced)
        callback = statusReceiver
    }
}
