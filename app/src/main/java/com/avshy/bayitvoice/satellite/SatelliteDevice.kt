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
import com.avshy.bayitvoice.music.LocalMediaPlayer
import com.avshy.bayitvoice.satellite.proto.MediaCommand
import com.avshy.bayitvoice.satellite.proto.MediaFeature
import com.avshy.bayitvoice.satellite.proto.MediaPlayerCommand
import com.avshy.bayitvoice.satellite.proto.MediaState
import com.avshy.bayitvoice.satellite.proto.MessageType
import com.avshy.bayitvoice.satellite.proto.Outgoing
import com.avshy.bayitvoice.satellite.proto.SetConfiguration
import com.avshy.bayitvoice.satellite.proto.SubscribeVoiceAssistant
import com.avshy.bayitvoice.satellite.proto.SwitchCommand
import com.avshy.bayitvoice.service.ListenGate
import com.avshy.bayitvoice.satellite.proto.TimerEvent
import com.avshy.bayitvoice.satellite.proto.VoiceAssistantEvent
import com.avshy.bayitvoice.satellite.proto.VoiceAssistantResponse
import com.avshy.bayitvoice.satellite.proto.VoiceFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private var scope: CoroutineScope? = null
    private var announceWatchdog: Job? = null
    private var listenJob: Job? = null
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

    /** This device's own media player (SPEC section 12); exists only while the setting is on. */
    private var mediaPlayer: LocalMediaPlayer? = null

    val name: String get() = "bayit-voice-" + settings.satelliteMac.takeLast(6)

    val friendlyName: String get() = settings.deviceName ?: Build.MODEL

    fun start(scope: CoroutineScope) {
        if (server != null) return
        this.scope = scope
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
        // A change made in the app reaches HA too.
        listenJob = scope.launch { ListenGate.paused(context).collect { sendListenState() } }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        advertiser?.unregister()
        advertiser = null
        server?.stop()
        server = null
        mediaPlayer?.shutdown()
        mediaPlayer = null
        _ready.value = false
    }

    /** The media-player setting changed: HA reconnects and lists the entities again. */
    fun onMediaPlayerSettingChanged() {
        if (!settings.localPlayerEnabled) {
            mediaPlayer?.shutdown()
            mediaPlayer = null
        }
        server?.dropConnection()
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
                send(Outgoing.listSwitch(LISTEN_OBJECT_ID, LISTEN_KEY, LISTEN_NAME, LISTEN_ICON))
                if (settings.localPlayerEnabled) send(
                    Outgoing.listMediaPlayer(MEDIA_OBJECT_ID, MEDIA_KEY, MEDIA_NAME, MEDIA_ICON, MEDIA_FEATURES, MEDIA_FORMATS),
                )
                alarmActions.listMessages().forEach(::send)
                send(Outgoing.empty(MessageType.LIST_ENTITIES_DONE_RESPONSE))
            }
            MessageType.SUBSCRIBE_STATES_REQUEST -> { sendListenState(); sendMediaState() }
            MessageType.MEDIA_PLAYER_COMMAND_REQUEST -> handleMediaCommand(MediaPlayerCommand.parse(message))
            MessageType.SWITCH_COMMAND_REQUEST -> {
                val command = SwitchCommand.parse(message)
                if (command.key == LISTEN_KEY) {
                    Log.i(TAG, "HA switched wake word listening ${if (command.state) "on" else "off"}")
                    ListenGate.setPaused(context, !command.state)
                    sendListenState()
                }
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
            MessageType.VOICE_ASSISTANT_ANNOUNCE_REQUEST -> {
                val request = AnnounceRequest.parse(message)
                Log.i(TAG, "Announcement (start conversation: ${request.startConversation})")
                // HA keeps the satellite "responding" until it hears back; never leave it waiting.
                announceWatchdog?.cancel()
                announceWatchdog = scope?.launch {
                    delay(ANNOUNCE_TIMEOUT_MS)
                    Log.w(TAG, "Announcement not finished in time; telling HA it failed")
                    announceWatchdog = null
                    send(Outgoing.announceFinished(false))
                }
                _announcements.emit(request)
            }
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

    private fun player(): LocalMediaPlayer =
        mediaPlayer ?: LocalMediaPlayer(context, ::sendMediaState).also { mediaPlayer = it }

    /** Reports the media player's state, volume and mute to HA; nothing while the feature is off. */
    private fun sendMediaState() {
        if (!settings.localPlayerEnabled) return
        val p = mediaPlayer
        send(Outgoing.mediaPlayerState(MEDIA_KEY, p?.state ?: MediaState.IDLE, p?.volume ?: 0.5f, p?.muted ?: false))
    }

    private fun handleMediaCommand(c: MediaPlayerCommand) {
        if (c.key != MEDIA_KEY || !settings.localPlayerEnabled) return
        Log.i(TAG, "Media command ${c.command} volume=${c.volume} url=${c.mediaUrl != null}")
        val p = player()
        c.volume?.let { p.setVolume(it) }
        c.mediaUrl?.takeIf { it.isNotEmpty() }?.let { p.play(it) }
        when (c.command) {
            MediaCommand.PLAY -> p.resume()
            MediaCommand.PAUSE -> p.pause()
            MediaCommand.STOP -> p.stop()
            MediaCommand.MUTE -> p.setMuted(true)
            MediaCommand.UNMUTE -> p.setMuted(false)
            MediaCommand.TOGGLE -> if (p.state == MediaState.PLAYING) p.pause() else p.resume()
            MediaCommand.VOLUME_UP -> p.setVolume(p.volume + VOLUME_STEP)
            MediaCommand.VOLUME_DOWN -> p.setVolume(p.volume - VOLUME_STEP)
            null -> Unit
        }
    }

    /** On = listening for the wake word. */
    private fun sendListenState() {
        send(Outgoing.switchState(LISTEN_KEY, !ListenGate.paused(context).value))
    }

    /** Tells HA an announcement (or a spoken reply) has finished playing. */
    fun announceFinished(success: Boolean) {
        announceWatchdog?.cancel()
        announceWatchdog = null
        send(Outgoing.announceFinished(success))
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

        /** The "Wake word listening" switch; the English name keeps the entity id the same in every language. */
        private const val LISTEN_OBJECT_ID = "wake_word_listening"
        private const val LISTEN_NAME = "Wake word listening"
        private const val LISTEN_ICON = "mdi:microphone"
        private val LISTEN_KEY = "bayit.$LISTEN_OBJECT_ID".hashCode()

        /** The media player entity (object id fixed in English so the entity id is the same in every language). */
        private const val MEDIA_OBJECT_ID = "media_player"
        private const val MEDIA_NAME = "Media player"
        private const val MEDIA_ICON = "mdi:speaker"
        private val MEDIA_KEY = "bayit.$MEDIA_OBJECT_ID".hashCode()
        private const val MEDIA_FEATURES = MediaFeature.PAUSE or MediaFeature.PLAY or MediaFeature.STOP or
            MediaFeature.VOLUME_SET or MediaFeature.VOLUME_MUTE or MediaFeature.PLAY_MEDIA or MediaFeature.MEDIA_ANNOUNCE
        private const val VOLUME_STEP = 0.1f

        /** What HA's proxy converts media to before sending it; ExoPlayer decodes FLAC natively. */
        private val MEDIA_FORMATS = listOf(
            Outgoing.MediaFormat("flac", 48000, 2, announcement = false),
            Outgoing.MediaFormat("flac", 16000, 1, announcement = true),
        )

        /** An announcement still unanswered after this is reported to HA as failed. */
        private const val ANNOUNCE_TIMEOUT_MS = 120_000L

        /** Reported as the ESPHome version; HA reads features from the flags, not from this. */
        const val ESPHOME_VERSION = "2025.9.0"

        @Volatile private var instance: SatelliteDevice? = null

        fun get(context: Context): SatelliteDevice =
            instance ?: synchronized(this) {
                instance ?: SatelliteDevice(context.applicationContext).also { instance = it }
            }
    }
}
