package com.beeregg2001.komorebi.viewmodel

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.NHKExclusionRepository
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class ReserveViewModelSingleReservationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun addReservationReportsSuccessOnlyAfterProviderSucceeds() = runTest(dispatcher) {
        val provider = FakeReserveProvider()
        val viewModel = viewModel(provider)
        val results = mutableListOf<SingleReservationResult>()

        viewModel.addReserve("program-1", results::add)
        runCurrent()

        assertEquals(listOf(SingleReservationResult.Success), results)
        assertFalse(viewModel.isSingleReservationSubmitting.value)
    }

    @Test fun providerFailureIsReportedAndDoesNotCallSuccess() = runTest(dispatcher) {
        val provider = FakeReserveProvider(addResult = Result.failure(IllegalStateException("rejected")))
        val viewModel = viewModel(provider)
        val results = mutableListOf<SingleReservationResult>()

        viewModel.addReserve("program-1", results::add)
        runCurrent()

        val failure = results.single() as SingleReservationResult.Failure
        assertEquals("RESERVATION_FAILED", failure.failure.code)
        assertFalse(viewModel.isSingleReservationSubmitting.value)
    }

    @Test fun duplicateSubmitIsRejectedWhileFirstRequestIsPending() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val provider = FakeReserveProvider(hold = hold)
        val viewModel = viewModel(provider)
        val first = mutableListOf<SingleReservationResult>()
        val second = mutableListOf<SingleReservationResult>()

        viewModel.addReserve("program-1", first::add)
        runCurrent()
        viewModel.addReserve("program-1", second::add)

        assertEquals(listOf(SingleReservationResult.AlreadySubmitting), second)
        hold.complete(Unit)
        runCurrent()
        assertEquals(listOf(SingleReservationResult.Success), first)
    }

    private fun viewModel(provider: ReserveProvider): ReserveViewModel {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        val exclusion = NHKExclusionRepository(settings)
        runBlocking { exclusion.state.first { it.isLoaded } }
        return ReserveViewModel(provider, exclusion)
    }

    private class FakeReserveProvider(
        private val addResult: Result<Unit> = Result.success(Unit),
        private val hold: CompletableDeferred<Unit>? = null,
    ) : ReserveProvider {
        override suspend fun getReserves() = Result.success(emptyList<ReserveItem>())
        override suspend fun addReserve(request: ReserveRequest): Result<Unit> {
            hold?.await()
            return addResult
        }
        override suspend fun updateReserve(reservationId: Int, request: ReserveRequest) = Result.success(Unit)
        override suspend fun deleteReservation(reservationId: Int) = Result.success(Unit)
        override suspend fun getReservationConditions() = Result.success(emptyList<ReservationCondition>())
        override suspend fun addReservationCondition(request: ReservationConditionAddRequest) = Result.success(Unit)
        override suspend fun updateReservationCondition(conditionId: Int, request: ReservationConditionUpdateRequest) =
            Result.failure<ReservationCondition>(UnsupportedOperationException())
        override suspend fun deleteReservationCondition(conditionId: Int) = Result.success(Unit)
    }
}
