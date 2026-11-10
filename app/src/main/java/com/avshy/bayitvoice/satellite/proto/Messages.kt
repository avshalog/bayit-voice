package com.avshy.bayitvoice.satellite.proto

/**
 * The subset of the ESPHome native API (`api.proto` from esphome/aioesphomeapi, MIT License)
 * that a voice satellite needs. In the API's naming HA is the client and this device the server.
 */
object MessageType {
    const val HELLO_REQUEST = 1
    const val HELLO_RESPONSE = 2
    const val CONNECT_REQUEST = 3
    const val CONNECT_RESPONSE = 4
    const val DISCONNECT_REQUEST = 5
    const val DISCONNECT_RESPONSE = 6
    const val PING_REQUEST = 7
    const val PING_RESPONSE = 8
    const val DEVICE_INFO_REQUEST = 9
    const val DEVICE_INFO_RESPONSE = 10
    const val LIST_ENTITIES_REQUEST = 11
    const val LIST_ENTITIES_DONE_RESPONSE = 19
    const val LIST_ENTITIES_SERVICES_RESPONSE = 41
    const val EXECUTE_SERVICE_REQUEST = 42
    const val EXECUTE_SERVICE_RESPONSE = 131
    const val SUBSCRIBE_HOMEASSISTANT_SERVICES_REQUEST = 34
    const val HOMEASSISTANT_ACTION_REQUEST = 35
    const val SUBSCRIBE_HOME_ASSISTANT_STATES_REQUEST = 38
    const val SUBSCRIBE_HOME_ASSISTANT_STATE_RESPONSE = 39
    const val HOME_ASSISTANT_STATE_RESPONSE = 40
    const val HOMEASSISTANT_ACTION_RESPONSE = 130
    const val SUBSCRIBE_VOICE_ASSISTANT_REQUEST = 89
    const val VOICE_ASSISTANT_REQUEST = 90
    const val VOICE_ASSISTANT_RESPONSE = 91
    const val VOICE_ASSISTANT_EVENT_RESPONSE = 92
    const val VOICE_ASSISTANT_AUDIO = 106
    const val VOICE_ASSISTANT_TIMER_EVENT_RESPONSE = 115
    const val VOICE_ASSISTANT_ANNOUNCE_REQUEST = 119
    const val VOICE_ASSISTANT_ANNOUNCE_FINISHED = 120
    const val VOICE_ASSISTANT_CONFIGURATION_REQUEST = 121
    const val VOICE_ASSISTANT_CONFIGURATION_RESPONSE = 122
    const val VOICE_ASSISTANT_SET_CONFIGURATION = 123
}

/** `VoiceAssistantFeature` bit flags reported in the device info. */
object VoiceFeature {
    const val VOICE_ASSISTANT = 1 shl 0
    const val API_AUDIO = 1 shl 2
    const val TIMERS = 1 shl 3
    const val ANNOUNCE = 1 shl 4
    const val START_CONVERSATION = 1 shl 5
}

/** `VoiceAssistantRequestFlag`. */
object VoiceRequestFlag {
    const val USE_VAD = 1
}

/** `VoiceAssistantEvent`. */
enum class VoiceEvent(val code: Int) {
    ERROR(0), RUN_START(1), RUN_END(2), STT_START(3), STT_END(4), INTENT_START(5), INTENT_END(6),
    TTS_START(7), TTS_END(8), WAKE_WORD_START(9), WAKE_WORD_END(10), STT_VAD_START(11), STT_VAD_END(12),
    TTS_STREAM_START(98), TTS_STREAM_END(99), INTENT_PROGRESS(100);

    companion object {
        fun of(code: Int): VoiceEvent? = entries.firstOrNull { it.code == code }
    }
}

/** `VoiceAssistantTimerEvent`. */
enum class TimerEventType { STARTED, UPDATED, CANCELLED, FINISHED }

/** A message as framed on the wire. */
class ApiMessage(val type: Int, val payload: ByteArray) {
    val fields: List<ProtoField> by lazy { ProtoReader.fields(payload) }
}

// --- Messages HA sends ---

data class HelloRequest(val clientInfo: String, val apiMajor: Int, val apiMinor: Int) {
    companion object {
        fun parse(m: ApiMessage) = HelloRequest(
            clientInfo = m.fields.firstOrNull { it.field == 1 }?.string.orEmpty(),
            apiMajor = m.fields.firstOrNull { it.field == 2 }?.int ?: 0,
            apiMinor = m.fields.firstOrNull { it.field == 3 }?.int ?: 0,
        )
    }
}

data class SubscribeVoiceAssistant(val subscribe: Boolean, val flags: Int) {
    companion object {
        fun parse(m: ApiMessage) = SubscribeVoiceAssistant(
            subscribe = m.fields.firstOrNull { it.field == 1 }?.bool ?: false,
            flags = m.fields.firstOrNull { it.field == 2 }?.int ?: 0,
        )
    }
}

data class VoiceAssistantResponse(val port: Int, val error: Boolean) {
    companion object {
        fun parse(m: ApiMessage) = VoiceAssistantResponse(
            port = m.fields.firstOrNull { it.field == 1 }?.int ?: 0,
            error = m.fields.firstOrNull { it.field == 2 }?.bool ?: false,
        )
    }
}

data class VoiceAssistantEvent(val type: VoiceEvent?, val data: Map<String, String>) {
    companion object {
        fun parse(m: ApiMessage): VoiceAssistantEvent {
            var type: VoiceEvent? = VoiceEvent.ERROR
            val data = HashMap<String, String>()
            for (f in m.fields) when (f.field) {
                1 -> type = VoiceEvent.of(f.int)
                2 -> {
                    val inner = ProtoReader.fields(f.bytes ?: ByteArray(0))
                    val name = inner.firstOrNull { it.field == 1 }?.string.orEmpty()
                    val value = inner.firstOrNull { it.field == 2 }?.string.orEmpty()
                    if (name.isNotEmpty()) data[name] = value
                }
            }
            return VoiceAssistantEvent(type, data)
        }
    }
}

data class TimerEvent(
    val type: TimerEventType?,
    val timerId: String,
    val name: String,
    val totalSeconds: Int,
    val secondsLeft: Int,
    val isActive: Boolean,
) {
    companion object {
        fun parse(m: ApiMessage): TimerEvent {
            val f = m.fields
            return TimerEvent(
                type = TimerEventType.entries.getOrNull(f.firstOrNull { it.field == 1 }?.int ?: 0),
                timerId = f.firstOrNull { it.field == 2 }?.string.orEmpty(),
                name = f.firstOrNull { it.field == 3 }?.string.orEmpty(),
                totalSeconds = f.firstOrNull { it.field == 4 }?.int ?: 0,
                secondsLeft = f.firstOrNull { it.field == 5 }?.int ?: 0,
                isActive = f.firstOrNull { it.field == 6 }?.bool ?: false,
            )
        }
    }
}

data class AnnounceRequest(
    val mediaId: String,
    val text: String,
    val preannounceMediaId: String,
    val startConversation: Boolean,
) {
    companion object {
        fun parse(m: ApiMessage): AnnounceRequest {
            val f = m.fields
            return AnnounceRequest(
                mediaId = f.firstOrNull { it.field == 1 }?.string.orEmpty(),
                text = f.firstOrNull { it.field == 2 }?.string.orEmpty(),
                preannounceMediaId = f.firstOrNull { it.field == 3 }?.string.orEmpty(),
                startConversation = f.firstOrNull { it.field == 4 }?.bool ?: false,
            )
        }
    }
}

/** `VoiceAssistantSetConfiguration`: the wake words HA asks to be active. */
data class SetConfiguration(val activeWakeWords: List<String>) {
    companion object {
        fun parse(m: ApiMessage) = SetConfiguration(m.fields.filter { it.field == 1 }.map { it.string })
    }
}

/** `HomeAssistantStateResponse`: an entity's state, or one attribute of it, sent by HA. */
data class HomeAssistantState(val entityId: String, val state: String, val attribute: String) {
    companion object {
        fun parse(m: ApiMessage) = HomeAssistantState(
            entityId = m.fields.firstOrNull { it.field == 1 }?.string.orEmpty(),
            state = m.fields.firstOrNull { it.field == 2 }?.string.orEmpty(),
            attribute = m.fields.firstOrNull { it.field == 3 }?.string.orEmpty(),
        )
    }
}

/** `ServiceArgType` for user-defined actions. */
object ServiceArgType {
    const val INT = 1
    const val STRING = 3
}

/** `SupportsResponseType` for user-defined actions. */
object SupportsResponse {
    const val NONE = 0
    const val OPTIONAL = 1
    const val ONLY = 2
}

/** One argument of an [ExecuteService] call, as sent by HA; read it by the declared type. */
class ServiceArg(private val fields: List<ProtoField>) {
    val string: String get() = fields.firstOrNull { it.field == 4 }?.string.orEmpty()
    val int: Int get() = fields.firstOrNull { it.field == 5 }?.sint ?: fields.firstOrNull { it.field == 2 }?.int ?: 0
}

/**
 * `ExecuteServiceRequest`: HA runs one of this device's user-defined actions. A non-zero
 * [callId] wants an `ExecuteServiceResponse`; [returnResponse] wants its JSON data too.
 */
data class ExecuteService(val key: Int, val args: List<ServiceArg>, val callId: Int, val returnResponse: Boolean) {
    companion object {
        fun parse(m: ApiMessage) = ExecuteService(
            key = m.fields.firstOrNull { it.field == 1 }?.number?.toInt() ?: 0,
            args = m.fields.filter { it.field == 2 }.map { ServiceArg(ProtoReader.fields(it.bytes ?: ByteArray(0))) },
            callId = m.fields.firstOrNull { it.field == 3 }?.int ?: 0,
            returnResponse = m.fields.firstOrNull { it.field == 4 }?.bool ?: false,
        )
    }
}

/** `HomeassistantActionResponse`: the result of an action this device asked HA to run. */
data class ActionResponse(val callId: Int, val success: Boolean, val error: String, val responseJson: String) {
    companion object {
        fun parse(m: ApiMessage) = ActionResponse(
            callId = m.fields.firstOrNull { it.field == 1 }?.int ?: 0,
            success = m.fields.firstOrNull { it.field == 2 }?.bool ?: false,
            error = m.fields.firstOrNull { it.field == 3 }?.string.orEmpty(),
            responseJson = m.fields.firstOrNull { it.field == 4 }?.bytes?.toString(Charsets.UTF_8).orEmpty(),
        )
    }
}

// --- Messages this device sends ---

object Outgoing {
    fun helloResponse(name: String, apiMajor: Int, apiMinor: Int, serverInfo: String) = ApiMessage(
        MessageType.HELLO_RESPONSE,
        ProtoWriter().uint32(1, apiMajor).uint32(2, apiMinor)
            .forcedString(3, serverInfo).forcedString(4, name).toByteArray(),
    )

    fun empty(type: Int) = ApiMessage(type, ByteArray(0))

    fun deviceInfo(
        name: String,
        friendlyName: String,
        macAddress: String,
        version: String,
        model: String,
        manufacturer: String,
        voiceFeatures: Int,
    ) = ApiMessage(
        MessageType.DEVICE_INFO_RESPONSE,
        ProtoWriter()
            .forcedString(2, name)
            .forcedString(3, macAddress)
            .forcedString(4, version)
            .forcedString(6, model)
            .forcedString(12, manufacturer)
            .forcedString(13, friendlyName)
            .uint32(17, voiceFeatures)
            .bool(19, true) // api_encryption_supported
            .toByteArray(),
    )

    fun voiceAssistantRequest(start: Boolean, conversationId: String? = null, wakeWordPhrase: String? = null) = ApiMessage(
        MessageType.VOICE_ASSISTANT_REQUEST,
        ProtoWriter()
            .bool(1, start)
            .string(2, conversationId)
            .uint32(3, if (start) VoiceRequestFlag.USE_VAD else 0)
            .string(5, wakeWordPhrase)
            .toByteArray(),
    )

    fun audio(data: ByteArray, end: Boolean = false) = ApiMessage(
        MessageType.VOICE_ASSISTANT_AUDIO,
        ProtoWriter().bytes(1, data).bool(2, end).toByteArray(),
    )

    fun announceFinished(success: Boolean) = ApiMessage(
        MessageType.VOICE_ASSISTANT_ANNOUNCE_FINISHED,
        ProtoWriter().bool(1, success).toByteArray(),
    )

    /** Asks HA to send an entity's state ([attribute] empty) or one of its attributes, now and on every change. */
    fun subscribeState(entityId: String, attribute: String = "") = ApiMessage(
        MessageType.SUBSCRIBE_HOME_ASSISTANT_STATE_RESPONSE,
        ProtoWriter().string(1, entityId).string(2, attribute.ifEmpty { null }).toByteArray(),
    )

    /**
     * Asks HA to run an action (`domain.service`) with string [data]. A non-zero [callId] asks for
     * the result, and [wantsResponse] for the action's response as JSON.
     */
    fun action(service: String, data: Map<String, String>, callId: Int, wantsResponse: Boolean) = ApiMessage(
        MessageType.HOMEASSISTANT_ACTION_REQUEST,
        ProtoWriter().apply {
            string(1, service)
            for ((key, value) in data) message(2, ProtoWriter().string(1, key).string(2, value))
            uint32(6, callId)
            bool(7, wantsResponse)
        }.toByteArray(),
    )

    /** Fires an HA event (its name must start with "esphome."); HA adds the device id to [data]. */
    fun event(name: String, data: Map<String, String>) = ApiMessage(
        MessageType.HOMEASSISTANT_ACTION_REQUEST,
        ProtoWriter().apply {
            string(1, name)
            for ((key, value) in data) message(2, ProtoWriter().string(1, key).string(2, value))
            bool(5, true) // is_event
        }.toByteArray(),
    )

    /** Lists one user-defined action; HA registers it as `esphome.<device name>_<name>`. */
    fun listService(name: String, key: Int, args: List<Pair<String, Int>>, supportsResponse: Int) = ApiMessage(
        MessageType.LIST_ENTITIES_SERVICES_RESPONSE,
        ProtoWriter().apply {
            string(1, name)
            fixed32(2, key)
            for ((argName, type) in args) message(3, ProtoWriter().string(1, argName).uint32(2, type))
            uint32(4, supportsResponse)
        }.toByteArray(),
    )

    /** Answers an [ExecuteService] call; [json] is sent only when HA asked for the response data. */
    fun executeServiceResponse(callId: Int, success: Boolean, error: String?, json: String?) = ApiMessage(
        MessageType.EXECUTE_SERVICE_RESPONSE,
        ProtoWriter().uint32(1, callId).bool(2, success).string(3, error)
            .bytes(4, json?.toByteArray(Charsets.UTF_8)).toByteArray(),
    )

    /** Wake words as (id, phrase); [active] are ids. */
    fun configuration(available: List<Pair<String, String>>, active: List<String>, maxActive: Int) = ApiMessage(
        MessageType.VOICE_ASSISTANT_CONFIGURATION_RESPONSE,
        ProtoWriter().apply {
            for ((id, phrase) in available) {
                message(1, ProtoWriter().string(1, id).string(2, phrase))
            }
            for (id in active) string(2, id)
            uint32(3, maxActive)
        }.toByteArray(),
    )
}
