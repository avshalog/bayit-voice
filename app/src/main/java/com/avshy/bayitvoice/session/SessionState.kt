package com.avshy.bayitvoice.session

import com.avshy.bayitvoice.ha.HaException

/** [Elsewhere]: another device nearer the speaker is answering this wake word. */
enum class SessionPhase { Idle, Connecting, Listening, Thinking, Speaking, Done, Error, Elsewhere }

/** Why a session failed. Mapped to localized strings by the UI. */
sealed interface SessionError {
    data object NotConfigured : SessionError
    data object InvalidUrl : SessionError
    data object InsecureUrl : SessionError
    data object AuthInvalid : SessionError
    data object Unreachable : SessionError
    data object Disconnected : SessionError
    data object Timeout : SessionError
    data object NoSpeech : SessionError
    data object Microphone : SessionError
    data object LocalNetworkDenied : SessionError

    /** An error reported by HA; [message] is HA's own text (already in the pipeline's language). */
    data class Server(val message: String) : SessionError
}

fun HaException.toSessionError(): SessionError = when (this) {
    is HaException.AuthInvalid -> SessionError.AuthInvalid
    is HaException.Unreachable -> SessionError.Unreachable
    is HaException.Disconnected -> SessionError.Disconnected
    is HaException.Protocol -> SessionError.Server(message.orEmpty())
    is HaException.CommandFailed -> SessionError.Server(serverMessage.ifEmpty { code })
}

enum class SessionNotice { PipelineMissing, OldHaVersion }

data class SessionState(
    val phase: SessionPhase = SessionPhase.Idle,
    val userText: String = "",
    val replyText: String = "",
    val error: SessionError? = null,
    val notice: SessionNotice? = null,
    /** With [SessionPhase.Elsewhere]: the name of the device that is answering. */
    val answeredBy: String? = null,
)

/**
 * What the wake-word service measured when it heard the wake word, handed to the session it
 * opened so the session can arbitrate with other devices (SPEC section 6.2).
 */
data class WakeDetection(
    /** `SystemClock.elapsedRealtime()` at detection. */
    val detectedAtMs: Long,
    val score: Float,
    /** dB the wake word rose above [noiseFloorRms]. */
    val snrDb: Float,
    /** The device's background level (RMS of 16-bit samples) just before the wake word. */
    val noiseFloorRms: Double,
)

/** Decides, with the other devices, whether this one answers; null means it does, else the winner's name. */
fun interface Arbitration {
    suspend fun winnerElsewhere(commandSnrDb: Float?): String?
}

/** How a whole conversation ended. */
enum class SessionEnd {
    /** Last turn succeeded with no follow-up: auto-close after the configured delay. */
    Completed,

    /** A hands-free turn (after a wake word or a follow-up question) heard no speech: close now. */
    Silent,

    /** Stay open and show the error. */
    Failed,

    /** Another device nearer the speaker answered: show who, briefly, then close without a sound. */
    Yielded,
}
