package com.beeregg2001.komorebi.data.jikkyo

import org.json.JSONArray
import org.json.JSONObject

/** Builds bounded NX-Jikkyo history requests without retaining a page of comment JSON. */
internal class JikkyoHistoryBackfill(
    private val lowerBoundUnixSeconds: Double,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    initialWhenUnixSeconds: Double,
) {
    private var pageCount = 0
    private var oldestUnixSeconds: Double? = null
    private var whenUnixSeconds = initialWhenUnixSeconds

    fun request(threadId: String, threadKey: String): String {
        val thread = JSONObject()
            .put("version", "20061206")
            .put("thread", threadId)
            .put("threadkey", threadKey)
            .put("user_id", "")
            .put("res_from", -pageSize)
            .put("when", whenUnixSeconds)
        return protocolEnvelope(thread)
    }

    fun acceptChat(text: String, onInRangeChat: (String) -> Unit) {
        val chat = try {
            JSONObject(text).optJSONObject("chat")
        } catch (_: Exception) {
            null
        } ?: return
        if (!chat.has("date")) return
        val timestamp = chat.getLong("date").toDouble() + chat.optLong("date_usec", 0L) / MICROS_PER_SECOND
        pageCount++
        oldestUnixSeconds = minOf(oldestUnixSeconds ?: timestamp, timestamp)
        if (timestamp >= lowerBoundUnixSeconds) onInRangeChat(text)
    }

    /** Advances the exclusive `when` cursor after the server echoes ping(rf:0). */
    fun advanceAfterPage(): Boolean {
        val oldest = oldestUnixSeconds ?: return false
        if (pageCount < pageSize || oldest <= lowerBoundUnixSeconds) return false
        // NX-Jikkyo queries `date < when`, so the next cursor is the oldest timestamp
        // itself. Subtracting one microsecond would skip the immediately preceding chat.
        if (oldest <= lowerBoundUnixSeconds) return false
        whenUnixSeconds = oldest
        pageCount = 0
        oldestUnixSeconds = null
        return true
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 500
        private const val MICROS_PER_SECOND = 1_000_000.0

        fun protocolEnvelope(thread: JSONObject): String = JSONArray()
            .put(JSONObject().put("ping", JSONObject().put("content", "rs:0")))
            .put(JSONObject().put("ping", JSONObject().put("content", "ps:0")))
            .put(JSONObject().put("thread", thread))
            .put(JSONObject().put("ping", JSONObject().put("content", "pf:0")))
            .put(JSONObject().put("ping", JSONObject().put("content", "rf:0")))
            .toString()
    }
}
