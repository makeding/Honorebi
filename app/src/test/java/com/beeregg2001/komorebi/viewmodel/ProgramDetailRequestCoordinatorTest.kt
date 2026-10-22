package com.beeregg2001.komorebi.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramDetailRequestCoordinatorTest {
    @Test
    fun duplicateFocusKeepsTheOriginalDebounceAndSingleRequest() = runTest {
        val calls = mutableListOf<Int>()
        val result = CompletableDeferred<Result<String>>()
        val accepted = mutableListOf<Int>()
        val published = mutableListOf<String>()
        val coordinator = coordinator(
            load = { id -> calls += id; result.await() },
            accepted = accepted,
            published = published,
        )

        assertTrue(coordinator.request(7))
        advanceTimeBy(200)
        assertFalse(coordinator.request(7))
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf(7), calls)
        assertEquals(listOf(7), accepted)
        result.complete(Result.success("detail-7"))
        runCurrent()
        assertEquals(listOf("detail-7"), published)

        // Completion releases the in-flight key so a later explicit focus can refresh metadata.
        assertTrue(coordinator.request(7))
        coordinator.cancel()
    }

    @Test
    fun supersededNonCooperativeRequestCannotPublishAfterNewFocus() = runTest {
        val results = mutableMapOf(
            1 to CompletableDeferred<Result<String>>(),
            2 to CompletableDeferred<Result<String>>(),
        )
        val calls = mutableListOf<Int>()
        val accepted = mutableListOf<Int>()
        val published = mutableListOf<String>()
        val coordinator = coordinator(
            load = { id ->
                calls += id
                awaitIgnoringCancellation(results.getValue(id))
            },
            accepted = accepted,
            published = published,
        )

        coordinator.request(1)
        advanceTimeBy(300)
        runCurrent()
        coordinator.request(2)
        advanceTimeBy(300)
        runCurrent()
        results.getValue(1).complete(Result.success("stale-1"))
        runCurrent()
        results.getValue(2).complete(Result.success("current-2"))
        runCurrent()

        assertEquals(listOf(1, 2), calls)
        assertEquals(listOf(1, 2), accepted)
        assertEquals(listOf("current-2"), published)
    }

    @Test
    fun clearRejectsLateResultAndFailureCanBeRetried() = runTest {
        val first = CompletableDeferred<Result<String>>()
        val attempts = ArrayDeque<CompletableDeferred<Result<String>>>(
            listOf(
                first,
                CompletableDeferred<Result<String>>(Result.failure(IOException("offline"))),
                CompletableDeferred<Result<String>>(Result.success("recovered")),
            ),
        )
        val published = mutableListOf<String>()
        val failures = mutableListOf<String?>()
        var cleared = 0
        val coordinator = coordinator(
            load = { awaitIgnoringCancellation(attempts.removeFirst()) },
            published = published,
            failures = failures,
        )

        coordinator.request(3)
        advanceTimeBy(300)
        runCurrent()
        coordinator.cancel { cleared += 1 }
        first.complete(Result.success("late"))
        runCurrent()
        assertEquals(1, cleared)
        assertTrue(published.isEmpty())

        coordinator.request(3)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("offline"), failures)
        assertTrue(coordinator.request(3))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("recovered"), published)
    }

    @Test
    fun independentProviderCancellationReleasesInflightKeyForRetry() = runTest {
        var attempts = 0
        val coordinator = coordinator(
            load = {
                attempts += 1
                throw CancellationException("provider cancelled independently")
            },
        )

        assertTrue(coordinator.request(5))
        advanceTimeBy(300)
        runCurrent()

        assertEquals(1, attempts)
        assertTrue(coordinator.request(5))
        coordinator.cancel()
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        load: suspend (Int) -> Result<String>,
        accepted: MutableList<Int> = mutableListOf(),
        published: MutableList<String> = mutableListOf(),
        failures: MutableList<String?> = mutableListOf(),
    ) = ProgramDetailRequestCoordinator(
        scope = backgroundScope,
        load = load,
        onRequestAccepted = accepted::add,
        onSuccess = published::add,
        onFailure = { failures += it.message },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private suspend fun <T> awaitIgnoringCancellation(result: CompletableDeferred<T>): T =
        try {
            result.await()
        } catch (_: CancellationException) {
            withContext(NonCancellable) { result.await() }
        }
}
