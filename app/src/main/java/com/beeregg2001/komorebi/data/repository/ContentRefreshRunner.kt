package com.beeregg2001.komorebi.data.repository

/** Main-thread single flight, retaining a recovery request behind an older in-flight request. */
internal class ContentRefreshRunner {
    var isRunning = false
        private set
    private var pending = false

    suspend fun run(queueIfRunning: Boolean = false, refresh: suspend () -> Unit) {
        if (isRunning) {
            pending = pending || queueIfRunning
            return
        }
        isRunning = true
        try {
            do {
                pending = false
                refresh()
            } while (pending)
        } finally {
            isRunning = false
            pending = false
        }
    }
}
