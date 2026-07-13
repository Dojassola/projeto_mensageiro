package com.mensageiro.core.signaling

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SignalingHub(private val receiverPeerId: String) {
    private data class Entry(
        val onPayload: (String) -> Unit,
        val onError: (String) -> Unit,
        @Volatile var waiting: Boolean = true,
        @Volatile var fastUntil: Long = System.currentTimeMillis() + FastWindow
    )

    private val signaling = SupabaseSignaling()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastRequestAt = 0L

    init {
        executor.scheduleWithFixedDelay(::poll, 0, Tick, TimeUnit.MILLISECONDS)
    }

    fun subscribe(peerId: String, onPayload: (String) -> Unit, onError: (String) -> Unit) {
        entries[peerId] = Entry(onPayload, onError)
        lastRequestAt = 0
    }

    fun unsubscribe(peerId: String) {
        entries.remove(peerId)
    }

    fun setWaiting(peerId: String, waiting: Boolean, fast: Boolean = false) {
        val entry = entries[peerId] ?: return
        entry.waiting = waiting
        if (fast) {
            entry.fastUntil = System.currentTimeMillis() + FastWindow
            lastRequestAt = 0
        }
    }

    fun close() {
        entries.clear()
        executor.shutdownNow()
    }

    private fun poll() {
        val waiting = entries.values.filter { it.waiting }
        if (waiting.isEmpty()) return
        val now = System.currentTimeMillis()
        val interval = if (waiting.any { it.fastUntil > now }) FastInterval else IdleInterval
        if (now - lastRequestAt < interval) return
        lastRequestAt = now

        runCatching { signaling.read(receiverPeerId) }
            .onSuccess { rows ->
                rows.forEach { (_, payload) ->
                    val sender = runCatching { SignalingCodec.decode(payload).senderPeerId }.getOrNull()
                    sender?.let { entries[it]?.onPayload?.invoke(payload) }
                }
            }
            .onFailure { error ->
                val message = error.message ?: "Falha ao consultar sinalizacao."
                waiting.forEach { it.onError(message) }
            }
    }

    private companion object {
        const val Tick = 5_000L
        const val FastInterval = 5_000L
        const val IdleInterval = 30_000L
        const val FastWindow = 90_000L
    }
}
