package com.avshy.bayitvoice.satellite

import android.content.Context
import android.os.Build
import android.util.Log
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.satellite.proto.ActionResponse
import com.avshy.bayitvoice.satellite.proto.AnnounceRequest
import com.avshy.bayitvoice.satellite.proto.HomeAssistantState
import com.avshy.bayitvoice.satellite.proto.ApiMessage
import com.avshy.bayitvoice.satellite.proto.ExecuteService
import com.avshy.bayitvoice.satellite.proto.HelloRequest
import com.avshy.bayitvoice.satellite.proto.MessageType
import com.avshy.bayitvoice.satellite.proto.Outgoing
import com.avshy.bayitvoice.satellite.proto.SetConfiguration
import com.avshy.bayitvoice.satellite.proto.SubscribeVoiceAssistant
import com.avshy.bayitvoice.satellite.proto.TimerEvent
import com.avshy.bayitvoice.satellite.proto.VoiceAssistantEvent
import com.avshy.bayitvoice.satellite.proto.VoiceAssistantResponse
import com.avshy.bayitvoice.satellite.proto.VoiceFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * This device as an ESPHome voice satellite (SPEC section 10): answers HA's API requests and
 * passes voice, timer and announcement messages on. One per process, started by the listening
 * service while satellite mode is on. Holds no UI.
 */
class SatelliteDevice private constructor(private val context: Context) {

    /** The wake-word models HA may choose from, as (id, name); and the active one's id. */
    interface WakeWords {
        fun available(): List<Pair<String, String>>
        fun activeId(): String
        fun activate(id: String)
    }

    private val settings = AppSettings.get(context)
    private var server: EspHomeServer? = null
    private var advertiser: SatelliteAdvertiser? = null
    var wakeWords: WakeWords? = null

    private val _ready = MutableStateFlow(false)

    /** True while HA is connected and subscribed to the voice assistant. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _voiceEvents = MutableSharedFlow<VoiceAssistantEvent>(extraBufferCapacity = 64)
    val voiceEvents: SharedFlow<VoiceAssistantEvent> = _voiceEvents.asSharedFlow()

    private val _runResponses = MutableSharedFlow<VoiceAssistantResponse>(extraBufferCapacity = 4)
    val runResponses: SharedFlow<VoiceAssistantResponse> = _runResponses.asSharedFlow()

    private val _timerEvents = MutableSharedFlow<TimerEvent>(replay = 0, extraBufferCapacity = 16)
    val timerEvents: SharedFlow<TimerEvent> = _timerEvents.asSharedFlow()

    private val _announcements = MutableSharedFlow<AnnounceRequest>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val announcements: SharedFlow<AnnounceRequest> = _announcements.asSharedFlow()

    /** HA states and actions for the idle screen (SPEC section 11). */
    val ha = HaLink(::send)

    /** Alarms and reminders HA sets on this device (SPEC section 10.1). */
    private val alarmActions = AlarmActions(context, ::send)

    val name: String get() = "bayit-voice-" + settings.satelliteMac.takeLast(6)

    val friendlyName: String get() = settings.deviceName ?: Build.MODEL

    fun start(scope: CoroutineScope) {
        if (server != null) return
        val mac = settings.satelliteMac
        server = EspHomeServer(
            port = PORT,
            key = settings.satelliteKey,
            name = name,
            mac = mac,
            onConnected = { },
            onMessage = ::handle,
            onDisconnected = { _ready.value = false; ha.onDisconnected() },
        ).also { it.start(scope) }
        advertiser = SatelliteAdvertiser(context, name, friendlyName, PORT, mac).also { it.register() }
    }

    fun stop() {
        advertiser?.unregister()
        advertiser = null
        server?.stop()
        server = null
        _ready.value = false
    }

    fun send(message: ApiMessage): Boolean = server?.send(message) ?: false

    private suspend fun handle(message: ApiMessage) {
        when (message.type) {
            MessageType.HELLO_REQUEST -> {
                val hello = HelloRequest.parse(message)
                Log.i(TAG, "Hello from '${hello.clientInfo}' (API ${hello.apiMajor}.${hello.apiMinor})")
                send(Outgoing.helloResponse(name, API_MAJOR, API_MINOR, SERVER_INFO))
            }
            // Legacy password login; an empty reply means "no password needed" (encryption authenticates).
            MessageType.CONNECT_REQUEST -> send(Outgoing.empty(MessageType.CONNECT_RESPONSE))
            MessageType.DEVICE_INFO_REQUEST -> send(
                Outgoing.deviceInfo(
                    name = name,
                    friendlyName = friendlyName,
                    macAddress = settings.satelliteMac.chunked(2).joinToString(":").uppercase(),
                    version = ESPHOME_VERSION,
                    model = "${Build.MANUFACTURER} ${Build.MODEL}".take(127),
                    manufacturer = MANUFACTURER,
                    voiceFeatures = VoiceFeature.VOICE_ASSISTANT or VoiceFeature.API_AUDIO or
                        VoiceFeature.TIMERS or VoiceFeature.ANNOUNCE or VoiceFeature.START_CONVERSATION,
                ),
            )
            MessageType.LIST_ENTITIES_REQUEST -> {
                alarmActions.listMessages().forEach(::send)
                send(Outgoing.empty(MessageType.LIST_ENTITIES_DONE_RESPONSE))
            }
            MessageType.EXECUTE_SERVICE_REQUEST -> alarmActions.execute(ExecuteService.parse(message))
            MessageType.PING_REQUEST -> send(Outgoing.empty(MessageType.PING_RESPONSE))
            MessageType.DISCONNECT_REQUEST -> {
                send(Outgoing.empty(MessageType.DISCONNECT_RESPONSE))
                _ready.value = false
            }
            MessageType.SUBSCRIBE_VOICE_ASSISTANT_REQUEST -> {
                val subscribe = SubscribeVoiceAssistant.parse(message)
                Log.i(TAG, "Voice assistant subscribe=${subscribe.subscribe} flags=${subscribe.flags}")
                _ready.value = subscribe.subscribe
            }
            MessageType.VOICE_ASSISTANT_RESPONSE -> _runResponses.emit(VoiceAssistantResponse.parse(message))
            MessageType.VOICE_ASSISTANT_EVENT_RESPONSE -> _voiceEvents.emit(VoiceAssistantEvent.parse(message))
            MessageType.VOICE_ASSISTANT_TIMER_EVENT_RESPONSE -> {
                val event = TimerEvent.parse(message)
                Log.i(TAG, "Timer ${event.type} '${event.name}' ${event.secondsLeft}/${event.totalSeconds} s")
                _timerEvents.emit(event)
            }
            MessageType.VOICE_ASSISTANT_ANNOUNCE_REQUEST -> _announcements.emit(AnnounceRequest.parse(message))
            MessageType.VOICE_ASSISTANT_CONFIGURATION_REQUEST -> sendConfiguration()
            MessageType.VOICE_ASSISTANT_SET_CONFIGURATION -> {
                val active = SetConfiguration.parse(message).activeWakeWords.firstOrNull()
                if (active != null && active != wakeWords?.activeId()) wakeWords?.activate(active)
            }
            MessageType.SUBSCRIBE_HOME_ASSISTANT_STATES_REQUEST -> ha.onStatesRequested()
            MessageType.HOME_ASSISTANT_STATE_RESPONSE -> ha.onState(HomeAssistantState.parse(message))
            MessageType.HOMEASSISTANT_ACTION_RESPONSE -> ha.onActionResponse(ActionResponse.parse(message))
            // HA subscribes to the actions a device may request; nothing to answer.
            MessageType.SUBSCRIBE_HOMEASSISTANT_SERVICES_REQUEST -> Unit
            else -> Log.d(TAG, "Ignoring message type ${message.type}")
        }
    }

    /** Fires an HA event from this device; HA adds the device id. Needs "Allow actions" in HA. */
    fun fireEvent(name: String, data: Map<String, String>): Boolean = send(Outgoing.event(name, data))

    /** Tells HA which wake words exist and which is active; also call after a local change. */
    fun sendConfiguration() {
        val words = wakeWords ?: return
        send(Outgoing.configuration(words.available(), listOf(words.activeId()), maxActive = 1))
    }

    companion object {
        private const val TAG = "SatelliteDevice"
        const val PORT = 6053
        private const val API_MAJOR = 1
        private const val API_MINOR = 10
        private const val SERVER_INFO = "Bayit Voice"
        private const val MANUFACTURER = "Bayit Voice"

        /** Reported as the ESPHome version; HA reads features from the flags, not from this. */
        const val ESPHOME_VERSION = "2025.9.0"

        @Volatile private var instance: SatelliteDevice? = null

        fun get(context: Context): SatelliteDevice =
            instance ?: synchronized(this) {
                instance ?: SatelliteDevice(context.applicationContext).also { instance = it }
            }
    }
}
