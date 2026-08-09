package com.beeregg2001.komorebi.ui.video.player.policy

/** Terminal reason reported to the root, which can then restore the committed item. */
enum class RecordedSwitchTerminalFailure {
    InitialUrlUnavailable,
    SessionRenewalFailed,
    PlaybackError,
}

/**
 * Per A -> B handoff budget. It deliberately has no knowledge of player or
 * network objects so every path which can abandon B is deterministic.
 */
class RecordedSwitchFailureBudget {
    private var initialUrlRetries = 0
    private var sessionRenewals = 0
    private var playbackReprepares = 0

    fun onInitialUrlUnavailable(): RecordedSwitchFailureDecision =
        if (initialUrlRetries++ == 0) RecordedSwitchFailureDecision.RetryInitialUrl
        else RecordedSwitchFailureDecision.Terminal(RecordedSwitchTerminalFailure.InitialUrlUnavailable)

    fun onHttp422(): RecordedSwitchFailureDecision =
        if (sessionRenewals++ == 0) RecordedSwitchFailureDecision.RenewSession
        else RecordedSwitchFailureDecision.Terminal(RecordedSwitchTerminalFailure.SessionRenewalFailed)

    fun onSessionRenewalUrlUnavailable(): RecordedSwitchFailureDecision =
        RecordedSwitchFailureDecision.Terminal(RecordedSwitchTerminalFailure.SessionRenewalFailed)

    fun onPlaybackError(): RecordedSwitchFailureDecision =
        if (playbackReprepares++ == 0) RecordedSwitchFailureDecision.Reprepare
        else RecordedSwitchFailureDecision.Terminal(RecordedSwitchTerminalFailure.PlaybackError)
}

sealed interface RecordedSwitchFailureDecision {
    data object RetryInitialUrl : RecordedSwitchFailureDecision
    data object RenewSession : RecordedSwitchFailureDecision
    data object Reprepare : RecordedSwitchFailureDecision
    data class Terminal(val failure: RecordedSwitchTerminalFailure) : RecordedSwitchFailureDecision
}
