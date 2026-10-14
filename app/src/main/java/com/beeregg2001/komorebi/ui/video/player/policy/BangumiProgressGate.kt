package com.beeregg2001.komorebi.ui.video.player.policy

/** Limits transport requests; watched-completion rules belong to HonomiTV. */
class BangumiProgressGate {
    private var inFlightProgramId: Int? = null
    private var lastProgramId: Int? = null
    private var nextReportAtMs = 0L

    fun begin(programId: Int, positionMs: Long, durationMs: Long, nowMs: Long): Boolean {
        if (positionMs < 0L || durationMs <= 0L || inFlightProgramId != null) return false
        if (lastProgramId == programId && nowMs < nextReportAtMs) return false
        lastProgramId = programId
        nextReportAtMs = nowMs + 30_000L
        inFlightProgramId = programId
        return true
    }

    fun finish(programId: Int) {
        if (inFlightProgramId == programId) inFlightProgramId = null
    }
}
