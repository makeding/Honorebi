package com.beeregg2001.komorebi.viewmodel

import androidx.compose.ui.test.junit4.createComposeRule
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.video.smb.SmbDirectoryReader
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class SmbRecoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retryPreservesPathQueryAndItemsAndRejectsLateDirectoryResults() {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        runBlocking { settings.saveString(SettingsRepository.SMB_SERVER_LIST,
            """[{"id":"test","name":"TV","ip":"server/share","port":"445","user":"","password":""}]""") }
        val reader = FakeReader()
        val vm = SmbViewModel(settings, RuntimeEnvironment.getApplication(), reader)
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val subscription = scope.launch { vm.fileList.collect() }
        try {
            vm.initSmb()
            drainUntil { reader.calls.isNotEmpty() && !vm.isLoading.value && vm.fileList.value.isNotEmpty() }
            val path = vm.currentPath.value
            reader.fail = true
            vm.loadDirectory(path)
            drainUntil { !vm.isLoading.value && vm.errorMessage.value != null }
            assertEquals("show.mp4", vm.fileList.value.single().name)
            reader.fail = false
            reader.hold = true
            val beforeRetry = reader.calls.size
            vm.retryCurrentRequest()
            vm.retryCurrentRequest()
            drainUntil { reader.pending != null }
            assertEquals(beforeRetry + 1, reader.calls.size)
            assertEquals(path, reader.calls.last())
            assertEquals("show.mp4", vm.fileList.value.single().name)
            reader.complete(listOf(item("show.mp4", path)))
            drainUntil { !vm.isLoading.value }
            assertNull(vm.errorMessage.value)

            reader.fail = true
            vm.searchFiles("show")
            drainUntil { !vm.isLoading.value && vm.errorMessage.value != null }
            reader.fail = false
            vm.retryCurrentRequest()
            drainUntil { !vm.isLoading.value && vm.fileList.value.isNotEmpty() }
            assertEquals("show", vm.activeSearchQuery.value)
            assertEquals(path, reader.calls.last())
            vm.searchFiles("absent")
            drainUntil { !vm.isLoading.value && vm.fileList.value.isEmpty() }
            assertNull(vm.errorMessage.value)

            reader.hold = true
            vm.loadDirectory("smb://server:445/old/")
            drainUntil { reader.pending != null }
            vm.loadDirectory("smb://server:445/new/")
            drainUntil { !vm.isLoading.value && vm.currentPath.value.endsWith("new/") }
            reader.complete(listOf(item("obsolete.mp4", "smb://server:445/old/")))
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertEquals("show.mp4", vm.fileList.value.single().name)
            assertNull(vm.errorMessage.value)
        } finally {
            subscription.cancel()
            scope.cancel()
        }
    }

    private fun drainUntil(condition: () -> Boolean) {
        compose.waitUntil(5_000) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            condition()
        }
    }

    private class FakeReader : SmbDirectoryReader() {
        val calls = mutableListOf<String>()
        var fail = false
        var hold = false
        var pending: Continuation<List<SmbItem>>? = null
        override suspend fun read(path: String, servers: List<SmbServer>): List<SmbItem> {
            calls += path
            if (fail) throw IOException("network")
            if (hold) {
                hold = false
                return suspendCoroutine { pending = it }
            }
            return listOf(item("show.mp4", path))
        }
        fun complete(items: List<SmbItem>) { val continuation = pending!!; pending = null; continuation.resume(items) }
    }

    companion object {
        private fun item(name: String, path: String) = SmbItem(name, path + name, false, 20, 1)
    }
}
