package com.avshy.bayitvoice.session

import com.avshy.bayitvoice.ha.HaException

enum class SessionPhase { Idle, Connecting, Listening, Thinking, Speaking, Done, Error }

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
)

/** How a whole conversation ended. */
enum class SessionEnd {
    /** Last turn succeeded with no follow-up: auto-close after the configured delay. */
    Completed,

    /** A hands-free turn (after a wake word or a follow-up question) heard no speech: close now. */
    Silent,

    /** Stay open and show the error. */
    Failed,

    /** Another device nearer the speaker took this wake word: close now, without a sound. */
    Yielded,
}
