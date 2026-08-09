package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedSwitchFailureBudgetTest {
    @Test
    fun initialUrl_onlyRetriesOnce() {
        val budget = RecordedSwitchFailureBudget()
        assertEquals(RecordedSwitchFailureDecision.RetryInitialUrl, budget.onInitialUrlUnavailable())
        assertEquals(
            RecordedSwitchFailureDecision.Terminal(RecordedSwitchTerminalFailure.InitialUrlUnavailable),
            budget.onInitialUrlUnavailable(),
        )
    }

    @Test
    fun expiredSession_onlyRenewsOnce_andEmptyRenewalIsTerminal() {
        val budget = RecordedSwitchFailureBudget()
        assertEquals(RecordedSwitchFailureDecision.RenewSession, budget.onHttp422())
        assertEquals(
            RecordedSwitchFailureDecision.Terminal(RecordedSwitchTerminalFailure.SessionRenewalFailed),
            budget.onSessionRenewalUrlUnavailable(),
        )
        assertEquals(
            RecordedSwitchFailureDecision.Terminal(RecordedSwitchTerminalFailure.SessionRenewalFailed),
            budget.onHttp422(),
        )
    }

    @Test
    fun ordinaryPlayerError_onlyRepreparesOnce() {
        val budget = RecordedSwitchFailureBudget()
        assertEquals(RecordedSwitchFailureDecision.Reprepare, budget.onPlaybackError())
        assertEquals(
            RecordedSwitchFailureDecision.Terminal(RecordedSwitchTerminalFailure.PlaybackError),
            budget.onPlaybackError(),
        )
    }
}
