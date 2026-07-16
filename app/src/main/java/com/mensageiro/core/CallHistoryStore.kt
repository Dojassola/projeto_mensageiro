package com.mensageiro.core

import android.content.Context
import com.mensageiro.core.crypto.IdentityStore
import org.json.JSONObject

enum class CallDirection { OUTGOING, INCOMING }
enum class CallEndedBy { ME, CONTACT, SYSTEM }
enum class CallResult { COMPLETED, MISSED, DECLINED, CANCELED, NO_ANSWER, BUSY, INTERRUPTED }
enum class CallHistoryStage { STARTED, CONNECTED, ENDED }

data class CallHistoryEvent(
    val callId: String,
    val stage: CallHistoryStage,
    val timestamp: Long,
    val direction: CallDirection? = null,
    val result: CallResult? = null,
    val endedBy: CallEndedBy? = null
)

data class CallRecord(
    val callId: String,
    val peerId: String,
    val direction: CallDirection,
    val startedAt: Long,
    val connectedAt: Long = 0,
    val endedAt: Long = 0,
    val result: CallResult? = null,
    val endedBy: CallEndedBy? = null
) {
    val duration: Long get() = if (connectedAt > 0 && endedAt >= connectedAt) endedAt - connectedAt else 0
}

class CallHistoryStore(context: Context, private val identityStore: IdentityStore) {
    private val prefs = context.applicationContext.getSharedPreferences("call_history", Context.MODE_PRIVATE)

    @Synchronized
    fun apply(peerId: String, event: CallHistoryEvent) {
        val current = get(event.callId)
        val updated = when (event.stage) {
            CallHistoryStage.STARTED -> CallRecord(
                event.callId,
                peerId,
                requireNotNull(event.direction),
                event.timestamp
            )
            CallHistoryStage.CONNECTED -> current?.copy(connectedAt = event.timestamp)
            CallHistoryStage.ENDED -> current?.copy(
                endedAt = event.timestamp,
                result = requireNotNull(event.result),
                endedBy = requireNotNull(event.endedBy)
            )
        } ?: return
        prefs.edit().putString(updated.callId, identityStore.protect(encode(updated))).apply()
        trim()
    }

    @Synchronized
    fun all(): List<CallRecord> = prefs.all.values.mapNotNull { value ->
        runCatching { decode(identityStore.unprotect(value as String)) }.getOrNull()
    }.sortedByDescending { it.startedAt }

    private fun get(callId: String): CallRecord? = prefs.getString(callId, null)?.let { value ->
        runCatching { decode(identityStore.unprotect(value)) }.getOrNull()
    }

    private fun trim() {
        if (prefs.all.size <= MaxRecords) return
        val remove = all().drop(MaxRecords)
        prefs.edit().also { editor -> remove.forEach { editor.remove(it.callId) } }.apply()
    }

    private fun encode(record: CallRecord) = JSONObject()
        .put("version", 1)
        .put("callId", record.callId)
        .put("peerId", record.peerId)
        .put("direction", record.direction.name)
        .put("startedAt", record.startedAt)
        .put("connectedAt", record.connectedAt)
        .put("endedAt", record.endedAt)
        .put("result", record.result?.name)
        .put("endedBy", record.endedBy?.name)
        .toString()

    private fun decode(text: String): CallRecord {
        val value = JSONObject(text)
        require(value.getInt("version") == 1)
        return CallRecord(
            value.getString("callId"),
            value.getString("peerId"),
            CallDirection.valueOf(value.getString("direction")),
            value.getLong("startedAt"),
            value.getLong("connectedAt"),
            value.getLong("endedAt"),
            value.optString("result").takeIf { it.isNotBlank() && it != "null" }?.let(CallResult::valueOf),
            value.optString("endedBy").takeIf { it.isNotBlank() && it != "null" }?.let(CallEndedBy::valueOf)
        )
    }

    private companion object {
        const val MaxRecords = 500
    }
}
