package com.avshy.bayitvoice.session

import android.util.Log
import com.avshy.bayitvoice.audio.Chime
import com.avshy.bayitvoice.audio.MicRecorder
import com.avshy.bayitvoice.audio.TtsPlayer
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.ServerUrl
import com.avshy.bayitvoice.ha.HaClient
import com.avshy.bayitvoice.ha.HaException
import com.avshy.bayitvoice.ha.HaVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * Runs Assist conversations against HA: streams the mic to `assist_pipeline/run`,
 * plays the TTS reply and loops while HA asks follow-up questions. Holds no UI.
 */
class AssistSession(
    private val settings: AppSettings,
    private val client: HaClient = HaClient(),
    private val mic: MicRecorder = MicRecorder(),
    private val tts: TtsPlayer = TtsPlayer(),
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private sealed interface TurnOutcome {
        data class Succeeded(val conversationId: String?, val continueConversation: Boolean) : TurnOutcome
        data object NoSpeech : TurnOutcome
        data class Failed(val error: SessionError) : TurnOutcome
    }

    /** Runs a conversation that starts with a manual turn. */
    suspend fun converse(): SessionEnd {
        val server = settings.server
        val token = settings.accessToken
        if (server == null || token.isNullOrEmpty()) return fail(SessionError.NotConfigured)
        if (!server.isAllowed) return fail(SessionError.InsecureUrl)

        return try {
            ensureConnected(server, token)
            val pipelineId = resolvePipeline()
            val allowContinue = checkContinueSupport()

            var conversationId: String? = null
            var followUp = false
            while (true) {
                if (followUp && settings.chimeEnabled) Chime.play()
                when (val turn = runTurn(server, pipelineId, conversationId, followUp)) {
                    is TurnOutcome.Failed -> return fail(turn.error)
                    TurnOutcome.NoSpeech ->
                        return if (followUp) {
                            _state.update { it.copy(phase = SessionPhase.Done) }
                            SessionEnd.FollowUpSilent
                        } else {
                            fail(SessionError.NoSpeech)
                        }
                    is TurnOutcome.Succeeded -> {
                        conversationId = turn.conversationId
                        if (turn.continueConversation && allowContinue) {
                            followUp = true
                        } else {
                            _state.update { it.copy(phase = SessionPhase.Done) }
                            return SessionEnd.Completed
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            SessionEnd.Failed
        } catch (e: HaException) {
            Log.w(TAG, "Session failed: ${e.javaClass.simpleName}")
            fail(e.toSessionError())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Microphone failure", e)
            fail(SessionError.Microphone)
        }
    }

    fun close() = client.close()

    private suspend fun ensureConnected(server: ServerUrl, token: String) {
        if (client.isConnected) return
        _state.update { it.copy(phase = SessionPhase.Connecting, error = null) }
        val version = client.connect(server.webSocketUrl, token)
        settings.lastHaVersion = version
    }

    /** Returns the stored pipeline id, or null (HA preferred) if it no longer exists. */
    private suspend fun resolvePipeline(): String? {
        val stored = settings.pipelineId ?: return null
        val list = client.listPipelines()
        if (list.pipelines.any { it.id == stored }) return stored
        settings.pipelineId = null
        _state.update { it.copy(notice = SessionNotice.PipelineMissing) }
        return null
    }

    private fun checkContinueSupport(): Boolean {
        if (HaVersion.supportsContinueConversation(client.haVersion)) return true
        if (!settings.oldVersionNoticeShown) {
            settings.oldVersionNoticeShown = true
            _state.update { it.copy(notice = SessionNotice.OldHaVersion) }
        }
        return false
    }

    private suspend fun runTurn(
        server: ServerUrl,
        pipelineId: String?,
        conversationId: String?,
        followUp: Boolean,
    ): TurnOutcome {
        _state.update { it.copy(phase = SessionPhase.Listening, userText = "", replyText = "", error = null) }

        val request = JSONObject()
            .put("start_stage", "stt")
            .put("end_stage", "tts")
            .put("input", JSONObject().put("sample_rate", MicRecorder.SAMPLE_RATE))
        pipelineId?.let { request.put("pipeline", it) }
        conversationId?.let { request.put("conversation_id", it) }
        val runId = client.send("assist_pipeline/run", request)

        var errorCode: String? = null
        var errorMessage = ""
        var responseType: String? = null
        var nextConversationId = conversationId
        var continueConversation = false
        var ttsUrl: String? = null
        var heardNoSpeech = false

        val finished = coroutineScope {
            val streamer = AudioStreamer(this)
            try {
                withTimeoutOrNull(RUN_TIMEOUT_MS) {
                    client.awaitResult(runId)
                    var timer: Job? = null
                    while (true) {
                        val msg = client.receive()
                        if (msg.optString("type") != "event" || msg.optInt("id") != runId) continue
                        val event = msg.getJSONObject("event")
                        val data = event.optJSONObject("data") ?: JSONObject()
                        when (event.optString("type")) {
                            "run-start" -> {
                                val handler = data.getJSONObject("runner_data").getInt("stt_binary_handler_id")
                                streamer.start(handler.toByte())
                                val silenceLimit = if (followUp) FOLLOW_UP_SILENCE_MS else FIRST_TURN_SILENCE_MS
                                timer = launch {
                                    delay(silenceLimit)
                                    heardNoSpeech = true
                                    streamer.end()
                                }
                            }
                            "stt-vad-start" -> {
                                timer?.cancel()
                                timer = launch {
                                    delay(SPEECH_SAFETY_MS)
                                    streamer.end()
                                }
                            }
                            "stt-vad-end" -> {
                                timer?.cancel()
                                streamer.end()
                                _state.update { it.copy(phase = SessionPhase.Thinking) }
                            }
                            "stt-end" -> {
                                timer?.cancel()
                                streamer.end()
                                val text = data.optJSONObject("stt_output")?.optString("text").orEmpty()
                                _state.update { it.copy(phase = SessionPhase.Thinking, userText = text) }
                            }
                            "intent-end" -> {
                                val output = data.optJSONObject("intent_output") ?: JSONObject()
                                output.optString("conversation_id").takeIf { it.isNotEmpty() }?.let { nextConversationId = it }
                                continueConversation = output.optBoolean("continue_conversation", false)
                                val response = output.optJSONObject("response")
                                responseType = response?.optString("response_type")
                                val speech = response?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty()
                                _state.update { it.copy(replyText = speech) }
                            }
                            "tts-end" -> {
                                ttsUrl = data.optJSONObject("tts_output")?.optString("url")?.takeIf { it.isNotEmpty() }
                            }
                            "error" -> {
                                errorCode = data.optString("code")
                                errorMessage = data.optString("message")
                                break
                            }
                            "run-end" -> break
                        }
                    }
                    timer?.cancel()
                    true
                } ?: false
            } finally {
                streamer.end()
            }
        }

        if (!finished) return TurnOutcome.Failed(SessionError.Timeout)
        errorCode?.let { code ->
            Log.i(TAG, "Pipeline error: $code")
            return if (heardNoSpeech || code == "stt-no-text-recognized") TurnOutcome.NoSpeech
            else TurnOutcome.Failed(SessionError.Server(errorMessage.ifEmpty { code }))
        }

        ttsUrl?.let { url ->
            _state.update { it.copy(phase = SessionPhase.Speaking) }
            runCatching { tts.play(server.resolve(url)) }
                .onFailure { Log.w(TAG, "TTS playback failed", it) }
        }

        if (responseType == "error") {
            return TurnOutcome.Failed(SessionError.Server(_state.value.replyText))
        }
        return TurnOutcome.Succeeded(nextConversationId, continueConversation)
    }

    /**
     * Records from the moment the turn starts, buffering until HA assigns the binary
     * handler id, then sends each chunk as `[handler id][PCM]` and finishes with `[handler id]`.
     */
    private inner class AudioStreamer(private val scope: CoroutineScope) {
        private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        private val handlerId = CompletableDeferred<Byte>()
        private var ended = false

        private val recorder = scope.launch {
            try {
                mic.record { buffer, length ->
                    val frame = ByteArray(length + 1)
                    System.arraycopy(buffer, 0, frame, 1, length)
                    chunks.trySend(frame)
                }
            } finally {
                chunks.close()
            }
        }

        private val sender = scope.launch {
            val id = handlerId.await()
            runCatching {
                for (frame in chunks) {
                    frame[0] = id
                    client.sendBinary(frame.toByteString())
                }
                client.sendBinary(byteArrayOf(id).toByteString())
            }.onFailure { Log.w(TAG, "Audio send stopped: ${it.javaClass.simpleName}") }
        }

        fun start(id: Byte) {
            handlerId.complete(id)
        }

        /** Stops the mic; the sender drains buffered audio and sends end-of-audio. */
        fun end() {
            if (ended) return
            ended = true
            recorder.cancel()
            if (!handlerId.isCompleted) sender.cancel()
        }
    }

    private fun fail(error: SessionError): SessionEnd {
        _state.update { it.copy(phase = SessionPhase.Error, error = error) }
        return SessionEnd.Failed
    }

    companion object {
        private const val TAG = "AssistSession"
        private const val RUN_TIMEOUT_MS = 60_000L
        private const val FIRST_TURN_SILENCE_MS = 10_000L
        private const val FOLLOW_UP_SILENCE_MS = 8_000L
        private const val SPEECH_SAFETY_MS = 10_000L
    }
}
