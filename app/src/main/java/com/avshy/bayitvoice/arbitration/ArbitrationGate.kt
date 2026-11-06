package com.avshy.bayitvoice.arbitration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands the arbitration result from the wake-word service to the session it opened.
 * The session opens and records at once; it only waits here before sending audio to HA,
 * so the arbitration window never delays the user (SPEC section 6.2).
 */
object ArbitrationGate {

    private enum class Verdict { None, Pending, Won, Lost }

    private val verdict = MutableStateFlow(Verdict.None)

    /** The service starts arbitrating for a new wake word. */
    fun begin() {
        verdict.value = Verdict.Pending
    }

    fun resolve(won: Boolean) {
        if (verdict.value == Verdict.Pending) verdict.value = if (won) Verdict.Won else Verdict.Lost
    }

    /** True when another device has already been chosen; lets a session skip its chime. */
    val lost: Boolean get() = verdict.value == Verdict.Lost

    /**
     * Waits for the result, consumes it, and returns whether this device should go on. No
     * arbitration in progress, or no answer in time, means "go on".
     */
    suspend fun awaitWin(): Boolean {
        val result = withTimeoutOrNull(MAX_WAIT_MS) { verdict.first { it != Verdict.Pending } } ?: Verdict.Won
        verdict.value = Verdict.None
        return result != Verdict.Lost
    }

    /** Forgets any result, e.g. when a session starts without a wake word. */
    fun clear() {
        verdict.value = Verdict.None
    }

    private const val MAX_WAIT_MS = 2_000L
}
