package com.avshy.bayitvoice.session

import android.util.Log
import android.os.SystemClock
import com.avshy.bayitvoice.audio.Chime
import com.avshy.bayitvoice.audio.MicLevel
import com.avshy.bayitvoice.audio.MicRecorder
import com.avshy.bayitvoice.audio.MicrophoneException
import com.avshy.bayitvoice.audio.TtsPlayer
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.BuildConfig
import com.avshy.bayitvoice.config.AssistLink
import com.avshy.bayitvoice.config.ServerUrl
import com.avshy.bayitvoice.satellite.SatelliteDevice
import com.avshy.bayitvoice.satellite.proto.AnnounceRequest
import com.avshy.bayitvoice.satellite.proto.Outgoing
import com.avshy.bayitvoice.satellite.proto.VoiceAssistantEvent
import com.avshy.bayitvoice.satellite.proto.VoiceEvent
import kotlinx.coroutines.CoroutineStart
import com.avshy.bayitvoice.ha.HaClient
import com.avshy.bayitvoice.timer.PhoneAlarms
import com.avshy.bayitvoice.ha.HaException
import com.avshy.bayitvoice.ha.HaVersion
import com.avshy.bayitvoice.ha.ConnectionManager
import com.avshy.bayitvoice.wakeword.EnergyVad
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
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Runs Assist conversations against HA: streams the mic to `assist_pipeline/run`,
 * plays the TTS reply and loops while HA asks follow-up questions. Holds no UI.
 */
class AssistSession(
    private val settings: AppSettings,
    private val connections: ConnectionManager,
    /** Used instead of [connections] while this device is an ESPHome satellite. */
    private val satellite: SatelliteDevice? = null,
    private val mic: MicRecorder = MicRecorder(),
    private val tts: TtsPlayer = TtsPlayer(),
    /**
     * Direct mode: answers an alarm request HA sends during the turn (SPEC section 10.1); gets
     * the request's event data and returns the answer's. Null: requests are not followed.
     */
    private val onAlarmRequest: ((JSONObject) -> JSONObject)? = null,
    /**
     * Words of a command only a satellite can run (a timer). A phone that answered a wake word
     * over a satellite hands such a command to it (SPEC section 6.2). Empty: never hand over.
     */
    private val handOffKeywords: List<String> = emptyList(),
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** The connection of the conversation in progress. */
    private lateinit var client: HaClient

    private sealed interface TurnOutcome {
        data class Succeeded(val conversationId: String?, val continueConversation: Boolean) : TurnOutcome
        data object NoSpeech : TurnOutcome
        data class Yielded(val answeredBy: String) : TurnOutcome
        data class Failed(val error: SessionError) : TurnOutcome
    }

    /**
     * Runs a conversation. [fromWakeWord] plays the listening chime before the first turn
     * (after a wake word); manual turns start silently.
     */
    suspend fun converse(
        fromWakeWord: Boolean = false,
        detection: WakeDetection? = null,
        arbitration: Arbitration? = null,
    ): SessionEnd {
        // As a satellite HA owns the pipeline and its settings; this device only needs HA connected.
        val device = satellite?.takeIf { settings.assistLink == AssistLink.Satellite }
        if (device == null && !settings.isConfigured) return fail(SessionError.NotConfigured)
        if (device != null && !device.ready.value) return fail(SessionError.SatelliteNotConnected)

        return try {
            val server = if (device == null) connect() else null
            val pipelineId = if (device == null) resolvePipeline() else null
            val allowContinue = device != null || checkContinueSupport()

            var conversationId: String? = null
            var followUp = false
            var followUps = 0
            // Hands-free turns (after a wake word or a follow-up question) end quietly on silence.
            var handsFree = fromWakeWord
            if (fromWakeWord && settings.chimeEnabled) Chime.play()
            // Only the first turn of a wake-word session is arbitrated; follow-ups stay here.
            var pendingArbitration = if (detection != null) arbitration else null
            while (true) {
                if (followUp && settings.chimeEnabled) Chime.play()
                val turn = if (device != null) {
                    runSatelliteTurn(device, conversationId, handsFree, detection, pendingArbitration)
                } else {
                    runTurn(server!!, pipelineId, conversationId, handsFree, detection, pendingArbitration)
                }
                pendingArbitration = null
                when (turn) {
                    is TurnOutcome.Yielded -> {
                        Log.i(TAG, "${turn.answeredBy} is nearer and answers; nothing was sent to HA")
                        _state.update { it.copy(phase = SessionPhase.Elsewhere, answeredBy = turn.answeredBy) }
                        return SessionEnd.Yielded
                    }
                    is TurnOutcome.Failed -> return fail(turn.error)
                    TurnOutcome.NoSpeech ->
                        return if (handsFree) {
                            _state.update { it.copy(phase = SessionPhase.Done) }
                            SessionEnd.Silent
                        } else {
                            fail(SessionError.NoSpeech)
                        }
                    is TurnOutcome.Succeeded -> {
                        conversationId = turn.conversationId
                        // Background speech (a TV, say) can keep a chain of follow-up questions going
                        // forever; after a few in a row the session ends and the wake word starts anew.
                        if (turn.continueConversation && allowContinue && followUps < MAX_FOLLOW_UPS) {
                            followUps++
                            followUp = true
                            handsFree = true
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
        } catch (e: MicrophoneException) {
            Log.w(TAG, "Microphone failure", e)
            fail(SessionError.Microphone)
        }
    }

    /**
     * Plays an announcement HA sent to this satellite (SPEC section 10): the pre-announce sound,
     * then the message, with its text on screen. Then tells HA it is done and, if HA asked for it,
     * listens for an answer as a hands-free conversation.
     */
    suspend fun announce(request: AnnounceRequest): SessionEnd {
        val device = satellite?.takeIf { settings.assistLink == AssistLink.Satellite } ?: return SessionEnd.Failed
        _state.update { SessionState(phase = SessionPhase.Speaking, replyText = request.text.withoutNiqqud()) }
        var played = true
        for (media in listOf(request.preannounceMediaId, request.mediaId)) {
            val url = satelliteTtsUrl(media.takeIf { it.isNotEmpty() }) ?: continue
            runCatching { tts.play(url) }.onFailure {
                played = false
                Log.w(TAG, "Announcement playback failed", it)
            }
        }
        device.announceFinished(played)
        if (request.startConversation && device.ready.value) return converse(fromWakeWord = true)
        _state.update { it.copy(phase = SessionPhase.Done) }
        return SessionEnd.Completed
    }

    /** Gets a live connection (reusing a kept one) and returns the address it reached. */
    private suspend fun connect(): ServerUrl {
        _state.update { it.copy(phase = SessionPhase.Connecting, error = null) }
        val connection = connections.acquire()
        client = connection.client
        return connection.server
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
        handsFree: Boolean,
        detection: WakeDetection?,
        arbitration: Arbitration?,
    ): TurnOutcome {
        _state.update { it.copy(phase = SessionPhase.Listening, userText = "", replyText = "", error = null) }

        val request = JSONObject()
            .put("start_stage", "stt")
            .put("end_stage", "tts")
            .put("input", JSONObject().put("sample_rate", MicRecorder.SAMPLE_RATE))
        pipelineId?.let { request.put("pipeline", it) }
        conversationId?.let { request.put("conversation_id", it) }

        var errorCode: String? = null
        var errorMessage = ""
        var responseType: String? = null
        var nextConversationId = conversationId
        var continueConversation = false
        var ttsUrl: String? = null
        var heardNoSpeech = false

        var answeredElsewhere: String? = null
        var spokeBeforeVerdict = false
        // A satellite this phone beat; a timer command is handed back to it (SPEC section 6.2).
        var handOffTo: Peer? = null
        val finished = coroutineScope {
            // Recording starts now, so nothing said meanwhile is lost; audio is buffered until HA
            // is ready, and HA hears nothing at all if another device wins the arbitration.
            val streamer = AudioStreamer(this, measure = arbitration != null)

            /** Follows one pipeline run to its end; false on timeout. */
            suspend fun follow(runRequest: JSONObject, alarmSub: Int?): Boolean {
                val runId = client.send("assist_pipeline/run", runRequest)
                Log.i(TAG, "Pipeline run $runId started (${runRequest.optString("start_stage")} to ${runRequest.optString("end_stage")})")
                return withTimeoutOrNull(RUN_TIMEOUT_MS) {
                    client.awaitResult(runId)
                    var timer: Job? = null
                    while (true) {
                        val msg = client.receive()
                        if (alarmSub != null && msg.optString("type") == "event" && msg.optInt("id") == alarmSub) {
                            answerAlarmRequest(msg)
                            continue
                        }
                        if (msg.optString("type") != "event" || msg.optInt("id") != runId) continue
                        val event = msg.getJSONObject("event")
                        val data = event.optJSONObject("data") ?: JSONObject()
                        when (event.optString("type")) {
                            "run-start" -> {
                                // Only a run that starts with speech has an audio handler.
                                val handler = data.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1
                                if (handler >= 0) {
                                    streamer.start(WebSocketSink(handler.toByte()))
                                    val silenceLimit = if (handsFree) HANDS_FREE_SILENCE_MS else MANUAL_SILENCE_MS
                                    timer = launch {
                                        delay(silenceLimit)
                                        heardNoSpeech = true
                                        streamer.end()
                                    }
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
                                _state.update { it.copy(replyText = speech.withoutNiqqud()) }
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
            }

            try {
                if (arbitration != null && detection != null) {
                    val verdict = arbitrate(streamer, detection, arbitration)
                    spokeBeforeVerdict = verdict.spoke
                    answeredElsewhere = verdict.result.winner?.name
                    if (answeredElsewhere != null) {
                        streamer.end()
                        return@coroutineScope true
                    }
                    if (handOffKeywords.isNotEmpty()) handOffTo = verdict.result.satelliteRunnerUp
                }
                // An alarm asked for in this turn reaches this phone as an HA event (needs an admin token).
                val alarmSub = onAlarmRequest?.let {
                    runCatching { client.send("subscribe_events", JSONObject().put("event_type", PhoneAlarms.REQUEST_EVENT)) }.getOrNull()
                }
                try {
                    val target = handOffTo
                    if (target == null) return@coroutineScope follow(request, alarmSub)
                    // A satellite heard it too: transcribe first, and if it is a timer, let the
                    // satellite run it (only it has HA timers); otherwise go on from the text here.
                    if (!follow(JSONObject(request.toString()).put("end_stage", "stt"), alarmSub)) return@coroutineScope false
                    val text = _state.value.userText
                    if (errorCode != null || text.isBlank()) return@coroutineScope true
                    if (isHandOff(text)) {
                        Log.i(TAG, "A timer command; handing it to ${target.name}")
                        arbitration!!.handOff(target)
                        answeredElsewhere = target.name
                        return@coroutineScope true
                    }
                    val fromText = JSONObject()
                        .put("start_stage", "intent")
                        .put("end_stage", "tts")
                        .put("input", JSONObject().put("text", text))
                    pipelineId?.let { fromText.put("pipeline", it) }
                    conversationId?.let { fromText.put("conversation_id", it) }
                    follow(fromText, alarmSub)
                } finally {
                    alarmSub?.let { id -> runCatching { client.send("unsubscribe_events", JSONObject().put("subscription", id)) } }
                }
            } finally {
                streamer.end()
            }
        }

        answeredElsewhere?.let { name ->
            // Keep showing "listening" until the user is done, so they don't stop or start over.
            if (handOffTo == null) waitForEndOfSpeech(spokeBeforeVerdict)
            return TurnOutcome.Yielded(name)
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

    /** Sets the alarm in the phone's clock app and tells the HA script it was handled here. */
    private fun answerAlarmRequest(msg: JSONObject) {
        val data = msg.optJSONObject("event")?.optJSONObject("data") ?: return
        val answer = runCatching { onAlarmRequest?.invoke(data) }.getOrNull() ?: return
        runCatching {
            client.send("fire_event", JSONObject().put("event_type", PhoneAlarms.ACK_EVENT).put("event_data", answer))
        }.onFailure { Log.w(TAG, "Could not answer the alarm request: ${it.javaClass.simpleName}") }
    }

    private class Verdict(val result: ArbitrationResult, val spoke: Boolean)

    private fun isHandOff(text: String): Boolean = handOffKeywords.any { text.contains(it, ignoreCase = true) }

    /** Every device decides at the same moment after its own detection; null winner = this one answers. */
    private suspend fun arbitrate(streamer: AudioStreamer, detection: WakeDetection, arbitration: Arbitration): Verdict {
        delay((detection.detectedAtMs + DECIDE_AFTER_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        val commandSnr = streamer.finishMeasuring(detection.noiseFloorRms)
        return Verdict(arbitration.decide(commandSnr), (commandSnr ?: 0f) >= SPEECH_SNR_DB)
    }

    /**
     * One turn as an ESPHome satellite (SPEC section 10): asks HA to start its pipeline, streams
     * the mic once HA's run starts, follows its events and plays the reply.
     */
    private suspend fun runSatelliteTurn(
        device: SatelliteDevice,
        conversationId: String?,
        handsFree: Boolean,
        detection: WakeDetection?,
        arbitration: Arbitration?,
    ): TurnOutcome {
        _state.update { it.copy(phase = SessionPhase.Listening, userText = "", replyText = "", error = null) }

        var errorCode: String? = null
        var errorMessage = ""
        var nextConversationId = conversationId
        var continueConversation = false
        var ttsUrl: String? = null
        var heardNoSpeech = false
        var notSent = false
        var answeredElsewhere: String? = null
        var spokeBeforeVerdict = false
        var waitedForSpeech = false
        // True from our start request until HA reports the run's end (or an error).
        var runOpen = false

        val finished = coroutineScope {
            val streamer = AudioStreamer(this, measure = arbitration != null)
            // Events go through one channel; subscribed before the request, so none is missed.
            val events = Channel<VoiceAssistantEvent>(Channel.UNLIMITED)
            val listeners = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) { device.voiceEvents.collect { events.send(it) } },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    device.runResponses.collect { if (it.error) events.send(VoiceAssistantEvent(VoiceEvent.ERROR, mapOf("code" to "start-refused"))) }
                },
            )
            try {
                if (arbitration != null && detection != null) {
                    val verdict = arbitrate(streamer, detection, arbitration)
                    spokeBeforeVerdict = verdict.spoke
                    val winner = verdict.result.winner
                    if (winner != null) {
                        var handedOver = false
                        if (!winner.satellite) {
                            // A phone answers, but may hand back a command only this satellite can
                            // run (a timer): keep recording until the user is done, then wait for it.
                            streamer.awaitEndOfSpeech(verdict.spoke, MAX_ELSEWHERE_LISTEN_MS)
                            streamer.stopRecording()
                            waitedForSpeech = true
                            handedOver = arbitration.awaitHandOff(HAND_OFF_WAIT_MS)
                        }
                        if (!handedOver) {
                            answeredElsewhere = winner.name
                            return@coroutineScope true
                        }
                        Log.i(TAG, "${winner.name} handed the command over; sending what was recorded")
                        _state.update { it.copy(phase = SessionPhase.Thinking) }
                    }
                }
                if (!device.send(Outgoing.voiceAssistantRequest(start = true, conversationId = conversationId))) {
                    notSent = true
                    return@coroutineScope true
                }
                runOpen = true
                Log.i(TAG, "Satellite run requested")
                withTimeoutOrNull(RUN_TIMEOUT_MS) {
                    var timer: Job? = null
                    for (event in events) {
                        // Debug builds: what HA heard and answered, to diagnose replies.
                        if (BuildConfig.DEBUG) Log.d(TAG, "Satellite event ${event.type} ${event.data}")
                        when (event.type) {
                            VoiceEvent.RUN_START -> {
                                streamer.start(SatelliteSink(device))
                                val silenceLimit = if (handsFree) HANDS_FREE_SILENCE_MS else MANUAL_SILENCE_MS
                                timer = launch {
                                    delay(silenceLimit)
                                    heardNoSpeech = true
                                    streamer.end()
                                    device.send(Outgoing.voiceAssistantRequest(start = false))
                                    events.send(VoiceAssistantEvent(VoiceEvent.RUN_END, emptyMap()))
                                }
                            }
                            VoiceEvent.STT_VAD_START -> {
                                timer?.cancel()
                                timer = launch {
                                    delay(SPEECH_SAFETY_MS)
                                    // HA's VAD never heard the end (e.g. steady background noise):
                                    // end the audio stream (SatelliteSink.finish), so HA transcribes
                                    // what it has and answers instead of waiting for more.
                                    Log.i(TAG, "No end of speech after ${SPEECH_SAFETY_MS / 1000} s; ending the audio")
                                    streamer.end()
                                }
                            }
                            VoiceEvent.STT_VAD_END -> {
                                timer?.cancel()
                                streamer.end()
                                _state.update { it.copy(phase = SessionPhase.Thinking) }
                            }
                            VoiceEvent.STT_END -> {
                                timer?.cancel()
                                streamer.end()
                                val text = event.data["text"].orEmpty()
                                _state.update { it.copy(phase = SessionPhase.Thinking, userText = text) }
                            }
                            VoiceEvent.INTENT_END -> {
                                event.data["conversation_id"]?.takeIf { it.isNotEmpty() }?.let { nextConversationId = it }
                                continueConversation = event.data["continue_conversation"] == "1"
                            }
                            VoiceEvent.TTS_START -> {
                                val text = event.data["text"].orEmpty()
                                _state.update { it.copy(replyText = text.withoutNiqqud()) }
                            }
                            VoiceEvent.TTS_END -> ttsUrl = event.data["url"]?.takeIf { it.isNotEmpty() }
                            VoiceEvent.ERROR -> {
                                runOpen = false
                                errorCode = event.data["code"].orEmpty()
                                errorMessage = event.data["message"].orEmpty()
                                break
                            }
                            VoiceEvent.RUN_END -> {
                                runOpen = false
                                break
                            }
                            else -> Unit
                        }
                    }
                    timer?.cancel()
                    true
                } ?: false
            } finally {
                streamer.end()
                listeners.forEach { it.cancel() }
                // Closed or timed out before HA ended the run: abort it there too (a stop request
                // aborts), or HA keeps it open and ends every later request at once.
                if (runOpen) {
                    runOpen = false
                    device.send(Outgoing.voiceAssistantRequest(start = false))
                }
            }
        }

        answeredElsewhere?.let { name ->
            if (!waitedForSpeech) waitForEndOfSpeech(spokeBeforeVerdict)
            return TurnOutcome.Yielded(name)
        }
        if (notSent) return TurnOutcome.Failed(SessionError.SatelliteNotConnected)
        // The run was already stopped in HA by the `finally` above.
        if (!finished) return TurnOutcome.Failed(SessionError.Timeout)
        errorCode?.let { code ->
            Log.i(TAG, "Satellite pipeline error: $code")
            return if (heardNoSpeech || code == "stt-no-text-recognized") TurnOutcome.NoSpeech
            else TurnOutcome.Failed(SessionError.Server(errorMessage.ifEmpty { code }))
        }
        if (heardNoSpeech) return TurnOutcome.NoSpeech

        satelliteTtsUrl(ttsUrl)?.let { url ->
            _state.update { it.copy(phase = SessionPhase.Speaking) }
            runCatching { tts.play(url) }.onFailure { Log.w(TAG, "TTS playback failed", it) }
        }
        // HA waits for this before it treats the reply as heard (and before a follow-up).
        device.announceFinished(true)
        return TurnOutcome.Succeeded(nextConversationId, continueConversation)
    }

    /** HA sends an absolute URL; a relative one is resolved against a configured address, if any. */
    private fun satelliteTtsUrl(url: String?): String? = when {
        url == null -> null
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> (settings.homeServer ?: settings.externalServer)?.resolve(url)
    }

    /** Where recorded audio goes once HA is ready for it. */
    private interface AudioSink {
        suspend fun send(pcm: ByteArray)
        suspend fun finish()
    }

    /** HA's WebSocket: each chunk as `[handler id][PCM]`, finished with `[handler id]`. */
    private inner class WebSocketSink(private val id: Byte) : AudioSink {
        override suspend fun send(pcm: ByteArray) {
            val frame = ByteArray(pcm.size + 1)
            frame[0] = id
            System.arraycopy(pcm, 0, frame, 1, pcm.size)
            client.sendBinary(frame.toByteString())
        }

        override suspend fun finish() {
            client.sendBinary(byteArrayOf(id).toByteString())
        }
    }

    /**
     * The ESPHome API: each chunk as a `VoiceAssistantAudio` message. Usually HA's VAD ends the
     * speech itself; when the app ends the audio first (the 10 s safety net), an empty message
     * with `end` set tells HA the stream is over, so it transcribes what it has. (A stop request
     * would instead abort the run with no answer.)
     */
    private class SatelliteSink(private val device: SatelliteDevice) : AudioSink {
        override suspend fun send(pcm: ByteArray) {
            device.send(Outgoing.audio(pcm))
        }

        override suspend fun finish() {
            device.send(Outgoing.audio(ByteArray(0), end = true))
        }
    }

    /**
     * Records from the moment the turn starts, buffering until HA is ready for audio, then
     * passes each chunk to the [AudioSink] given to [start].
     */
    private inner class AudioStreamer(private val scope: CoroutineScope, measure: Boolean = false) {
        private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        private val sink = CompletableDeferred<AudioSink>()
        private var ended = false

        // Level of the audio recorded so far, for arbitration; guarded by `this`.
        private var measuring = measure
        private var sumSquares = 0.0
        private var samples = 0L

        /** Sees each chunk as it is recorded, e.g. to notice the end of speech. */
        @Volatile private var onAudio: ((ByteArray, Int) -> Unit)? = null

        private val recorder = scope.launch {
            try {
                mic.record { buffer, length ->
                    chunks.trySend(buffer.copyOf(length))
                    MicLevel.update(buffer, length)
                    if (measuring) accumulate(buffer, length)
                    onAudio?.invoke(buffer, length)
                }
            } finally {
                MicLevel.reset()
                chunks.close()
            }
        }

        private val sender = scope.launch {
            val target = sink.await()
            try {
                for (chunk in chunks) target.send(chunk)
                target.finish()
            } catch (e: HaException) {
                // The receive loop reports the dropped connection; just stop sending.
                Log.w(TAG, "Audio send stopped: ${e.javaClass.simpleName}")
            }
        }

        fun start(target: AudioSink) {
            sink.complete(target)
        }

        private fun accumulate(buffer: ByteArray, length: Int) = synchronized(this) {
            var i = 0
            while (i + 1 < length) {
                val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toDouble()
                sumSquares += sample * sample
                i += 2
            }
            samples += length / 2
        }

        /** Stops measuring; returns dB above [noiseFloorRms] of what was recorded, or null if nothing was. */
        fun finishMeasuring(noiseFloorRms: Double): Float? = synchronized(this) {
            measuring = false
            if (samples == 0L) return null
            val rms = sqrt(sumSquares / samples)
            (20 * log10(rms / max(noiseFloorRms, 1.0))).toFloat()
        }

        /** Stops the mic but keeps what was recorded, for a sink started later. */
        fun stopRecording() {
            recorder.cancel()
        }

        /**
         * Returns once the user stops speaking (as [waitForEndOfSpeech], but on this recording, so
         * the audio is kept), or after [maxMs].
         */
        suspend fun awaitEndOfSpeech(spokeAlready: Boolean, maxMs: Long) {
            val done = CompletableDeferred<Unit>()
            val vad = EnergyVad()
            var heardSpeech = spokeAlready
            var silentMs = 0
            onAudio = { buffer, length ->
                val frame = ShortArray(length / 2) { i -> ((buffer[2 * i].toInt() and 0xFF) or (buffer[2 * i + 1].toInt() shl 8)).toShort() }
                if (vad.isVoice(frame)) {
                    heardSpeech = true
                    silentMs = 0
                } else {
                    silentMs += frame.size * 1000 / MicRecorder.SAMPLE_RATE
                }
                if (silentMs >= if (heardSpeech) END_SILENCE_MS else NO_SPEECH_MS) done.complete(Unit)
            }
            withTimeoutOrNull(maxMs) { done.await() }
            onAudio = null
        }

        /** Stops the mic; the sender drains buffered audio and sends end-of-audio. */
        fun end() {
            if (ended) return
            ended = true
            recorder.cancel()
            if (!sink.isCompleted) sender.cancel()
        }
    }

    /**
     * Listens locally, sending nothing, until the user stops speaking: a short silence after
     * speech, a longer one if no speech comes at all, and never longer than a cap.
     */
    private suspend fun waitForEndOfSpeech(spokeAlready: Boolean) = coroutineScope {
        val done = CompletableDeferred<Unit>()
        val vad = EnergyVad()
        var heardSpeech = spokeAlready
        var silentMs = 0
        val listener = launch {
            mic.recordFrames(VAD_FRAME_SAMPLES) { frame ->
                if (vad.isVoice(frame)) {
                    heardSpeech = true
                    silentMs = 0
                } else {
                    silentMs += VAD_FRAME_MS
                }
                if (silentMs >= if (heardSpeech) END_SILENCE_MS else NO_SPEECH_MS) done.complete(Unit)
            }
        }
        withTimeoutOrNull(MAX_ELSEWHERE_LISTEN_MS) { done.await() }
        listener.cancel()
    }

    private fun fail(error: SessionError): SessionEnd {
        _state.update { it.copy(phase = SessionPhase.Error, error = error) }
        return SessionEnd.Failed
    }

    companion object {
        private const val TAG = "AssistSession"
        private const val RUN_TIMEOUT_MS = 60_000L
        private const val MANUAL_SILENCE_MS = 10_000L
        private const val HANDS_FREE_SILENCE_MS = 8_000L

        /** Follow-up questions in a row before a session ends, so background speech cannot keep one going. */
        private const val MAX_FOLLOW_UPS = 2
        private const val SPEECH_SAFETY_MS = 10_000L

        /** Arbitration happens this long after the wake word: it then also hears the command's start. */
        private const val DECIDE_AFTER_MS = 1_500L

        /** The start of the command counts as speech when this many dB above the noise floor. */
        private const val SPEECH_SNR_DB = 10f

        // How a device that lost the arbitration waits for the user to finish.
        private const val VAD_FRAME_SAMPLES = 640 // 40 ms
        private const val VAD_FRAME_MS = 40
        private const val END_SILENCE_MS = 800
        private const val NO_SPEECH_MS = 1_200
        private const val MAX_ELSEWHERE_LISTEN_MS = 10_000L

        /** A satellite that lost to a phone waits this long after the command for the phone to hand it over. */
        private const val HAND_OFF_WAIT_MS = 4_000L
    }
}
