package com.avshy.bayitvoice.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.avshy.bayitvoice.wakeword.WakeWordModels
import java.security.SecureRandom

/** How the HA WebSocket is held between sessions (SPEC section 7.3). */
enum class ConnectionMode { KeepConnected, OnDemand }

/**
 * How this device reaches HA (SPEC section 10): [Direct] is the app's own WebSocket client;
 * [Satellite] makes it an ESPHome voice satellite that HA connects to.
 */
enum class AssistLink { Direct, Satellite }

/** How a conversation is shown (SPEC section 5.1): the wave sheet, the orb sheet, or the whole screen. */
enum class SessionStyle { Wave, Futuristic, FullScreen }

/**
 * All user configuration. Non-secret values live in plain preferences;
 * the access token lives in [SecretStore] and is never logged.
 */
class AppSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val secrets = SecretStore(context)

    init {
        migrate(context)
    }

    /** Address used on the home network. */
    var homeUrl: String?
        get() = prefs.getString(KEY_HOME_URL, null)
        set(value) = prefs.edit { putString(KEY_HOME_URL, value) }

    /** Address used away from home. */
    var externalUrl: String?
        get() = prefs.getString(KEY_EXTERNAL_URL, null)
        set(value) = prefs.edit { putString(KEY_EXTERNAL_URL, value) }

    /** SSIDs of the home Wi-Fi networks; empty = decide by trying the home address first. */
    var homeSsids: Set<String>
        get() = prefs.getStringSet(KEY_HOME_SSIDS, emptySet()).orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_HOME_SSIDS, value) }

    var accessToken: String?
        get() = secrets.get(KEY_TOKEN)
        set(value) = secrets.put(KEY_TOKEN, value)

    /** Null means "Default (HA preferred pipeline)". */
    var pipelineId: String?
        get() = prefs.getString(KEY_PIPELINE, null)
        set(value) = prefs.edit { putString(KEY_PIPELINE, value) }

    /** Seconds before closing after a successful turn; 0 = never. */
    var autoCloseSeconds: Int
        get() = prefs.getInt(KEY_AUTO_CLOSE, DEFAULT_AUTO_CLOSE_SECONDS)
        set(value) = prefs.edit { putInt(KEY_AUTO_CLOSE, value.coerceIn(0, 600)) }

    var chimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHIME, true)
        set(value) = prefs.edit { putBoolean(KEY_CHIME, value) }

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_WAKE_ENABLED, value) }

    /** Satellite mode: the service runs but does not listen for the wake word (the HA switch is off). */
    var wakeListenPaused: Boolean
        get() = prefs.getBoolean(KEY_WAKE_PAUSED, false)
        set(value) = prefs.edit { putBoolean(KEY_WAKE_PAUSED, value) }

    var wakeModelId: String
        get() = prefs.getString(KEY_WAKE_MODEL, null) ?: WakeWordModels.DEFAULT_MODEL_ID
        set(value) = prefs.edit { putString(KEY_WAKE_MODEL, value) }

    /** Detection threshold for the wake-word score, 0..1. */
    var wakeThreshold: Float
        get() = prefs.getFloat(KEY_WAKE_THRESHOLD, DEFAULT_WAKE_THRESHOLD)
        set(value) = prefs.edit { putFloat(KEY_WAKE_THRESHOLD, value.coerceIn(0.05f, 0.95f)) }

    /** Set when the service could not load the selected model; shown in settings until retried. */
    var wakeModelLoadFailed: Boolean
        get() = prefs.getBoolean(KEY_WAKE_LOAD_FAILED, false)
        set(value) = prefs.edit { putBoolean(KEY_WAKE_LOAD_FAILED, value) }

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) = prefs.edit { putBoolean(KEY_START_ON_BOOT, value) }

    var connectionMode: ConnectionMode
        get() = prefs.getString(KEY_CONNECTION_MODE, null)
            ?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() } ?: ConnectionMode.KeepConnected
        set(value) = prefs.edit { putString(KEY_CONNECTION_MODE, value.name) }

    /** Only the nearest device answers when several hear the wake word (SPEC section 6.2). */
    var arbitrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ARBITRATION, true)
        set(value) = prefs.edit { putBoolean(KEY_ARBITRATION, value) }

    /** The name other devices show for this one; null = the system's device name. */
    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_DEVICE_NAME, value?.trim()) }

    /**
     * A fingerprint of the HA instance ([com.avshy.bayitvoice.ha.HaClient.instanceKey]): the
     * same on every device of one home, whatever address each one uses.
     */
    var haInstanceKey: String?
        get() = prefs.getString(KEY_HA_INSTANCE, null)
        set(value) = prefs.edit { putString(KEY_HA_INSTANCE, value) }

    /** Which devices arbitrate together: the HA instance when known, else its address. Null when neither exists. */
    val arbitrationGroupId: String?
        get() = haInstanceKey?.let { "instance:$it" }
            ?: (homeServer ?: externalServer)?.base?.let { "ha:$it" }

    /** How this device shows a conversation (SPEC section 5.1). */
    var sessionStyle: SessionStyle
        get() = prefs.getString(KEY_SESSION_STYLE, null)
            ?.let { runCatching { SessionStyle.valueOf(it) }.getOrNull() } ?: SessionStyle.Wave
        set(value) = prefs.edit { putString(KEY_SESSION_STYLE, value.name) }

    /**
     * The style a conversation is shown in: a satellite is a device fixed in the home, so it is
     * always full screen; the chosen [sessionStyle] stays saved for the direct link.
     */
    val effectiveSessionStyle: SessionStyle
        get() = if (assistLink == AssistLink.Satellite) SessionStyle.FullScreen else sessionStyle

    /** This device's random, stable id for arbitration; never tied to the hardware. */
    val deviceId: Long
        get() = prefs.getLong(KEY_DEVICE_ID, 0L).takeIf { it != 0L }
            ?: (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1L)
                .also { prefs.edit { putLong(KEY_DEVICE_ID, it) } }

    var assistLink: AssistLink
        get() = prefs.getString(KEY_ASSIST_LINK, null)
            ?.let { runCatching { AssistLink.valueOf(it) }.getOrNull() } ?: AssistLink.Direct
        set(value) = prefs.edit { putString(KEY_ASSIST_LINK, value.name) }

    /** The satellite's Noise key, base64 of 32 random bytes; created on first use, kept encrypted. */
    val satelliteKey: String
        get() = secrets.get(KEY_SATELLITE_KEY) ?: run {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
                .also { secrets.put(KEY_SATELLITE_KEY, it) }
        }

    /** A random, locally administered MAC address that names this satellite to HA; 12 lowercase hex digits. */
    val satelliteMac: String
        get() = prefs.getString(KEY_SATELLITE_MAC, null) ?: run {
            val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
            bytes.joinToString("") { "%02x".format(it) }.also { prefs.edit { putString(KEY_SATELLITE_MAC, it) } }
        }

    /** Idle screen (SPEC section 11): night mode starts at this minute of the day. */
    var idleNightStartMinute: Int
        get() = prefs.getInt(KEY_IDLE_NIGHT_START, DEFAULT_NIGHT_START)
        set(value) = prefs.edit { putInt(KEY_IDLE_NIGHT_START, value.coerceIn(0, MINUTES_PER_DAY - 1)) }

    /** Idle screen: night mode ends at this minute of the day. Equal to the start = night mode off. */
    var idleNightEndMinute: Int
        get() = prefs.getInt(KEY_IDLE_NIGHT_END, DEFAULT_NIGHT_END)
        set(value) = prefs.edit { putInt(KEY_IDLE_NIGHT_END, value.coerceIn(0, MINUTES_PER_DAY - 1)) }

    /** Idle screen: show the Hebrew date under the Gregorian one. Null = follow the UI language. */
    var idleHebrewDate: Boolean?
        get() = if (prefs.contains(KEY_IDLE_HEBREW_DATE)) prefs.getBoolean(KEY_IDLE_HEBREW_DATE, false) else null
        set(value) = prefs.edit { if (value == null) remove(KEY_IDLE_HEBREW_DATE) else putBoolean(KEY_IDLE_HEBREW_DATE, value) }

    /** Idle screen: backlight level 0..1 during the day; null = follow the system (including auto-brightness). */
    var idleDayBrightness: Float?
        get() = prefs.getFloat(KEY_IDLE_DAY_BRIGHTNESS, -1f).takeIf { it >= 0f }
        set(value) = prefs.edit { putFloat(KEY_IDLE_DAY_BRIGHTNESS, value?.coerceIn(0.02f, 1f) ?: -1f) }

    /** Idle screen: at night turn the screen off instead of showing a dim red clock. */
    var idleNightScreenOff: Boolean
        get() = prefs.getBoolean(KEY_IDLE_NIGHT_SCREEN_OFF, false)
        set(value) = prefs.edit { putBoolean(KEY_IDLE_NIGHT_SCREEN_OFF, value) }

    /** Idle screen: open it by itself while charging (for systems without Android's screen saver). */
    /** Satellite mode: this device is also a media player in HA (SPEC section 12). Off by default. */
    var localPlayerEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_PLAYER, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCAL_PLAYER, value) }

    var idleAutoStart: Boolean
        get() = prefs.getBoolean(KEY_IDLE_AUTO_START, false)
        set(value) = prefs.edit { putBoolean(KEY_IDLE_AUTO_START, value) }

    /** Idle screen: the HA `weather` entity it shows; null = no weather. */
    var idleWeatherEntity: String?
        get() = prefs.getString(KEY_IDLE_WEATHER, null)?.takeIf { it.startsWith("weather.") }
        set(value) = prefs.edit { putString(KEY_IDLE_WEATHER, value?.trim()?.lowercase()) }

    /** Idle screen: the media player chosen above is this device's own (SPEC section 12), so play/pause and volume act on it directly. */
    var idleMediaPlayerLocal: Boolean
        get() = prefs.getBoolean(KEY_IDLE_MEDIA_LOCAL, false)
        set(value) = prefs.edit { putBoolean(KEY_IDLE_MEDIA_LOCAL, value) }

    /** Idle screen: the room's HA `media_player`, shown while it plays; null = no music card. */
    var idleMediaPlayer: String?
        get() = prefs.getString(KEY_IDLE_MEDIA_PLAYER, null)?.takeIf { it.startsWith("media_player.") }
        set(value) = prefs.edit { putString(KEY_IDLE_MEDIA_PLAYER, value?.trim()?.lowercase()) }

    /** Satellite alarms and reminders (SPEC section 10.1), as the JSON array DeviceAlarms writes. */
    var deviceAlarmsJson: String?
        get() = prefs.getString(KEY_DEVICE_ALARMS, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_ALARMS, value) }

    /** HA version from the last successful `auth_ok`. */
    var lastHaVersion: String?
        get() = prefs.getString(KEY_HA_VERSION, null)
        set(value) = prefs.edit { putString(KEY_HA_VERSION, value) }

    var oldVersionNoticeShown: Boolean
        get() = prefs.getBoolean(KEY_OLD_VERSION_NOTICE, false)
        set(value) = prefs.edit { putBoolean(KEY_OLD_VERSION_NOTICE, value) }

    val homeServer: ServerUrl? get() = homeUrl?.let(ServerUrl::parse)

    val externalServer: ServerUrl? get() = externalUrl?.let(ServerUrl::parse)

    val isConfigured: Boolean
        get() = (homeServer != null || externalServer != null) && !accessToken.isNullOrEmpty()

    fun signOut() {
        secrets.put(KEY_TOKEN, null)
        prefs.edit { remove(KEY_HA_VERSION) }
    }

    /** Moves values written by earlier versions to their current keys. */
    private fun migrate(context: Context) {
        prefs.getString(LEGACY_KEY_URL, null)?.let { legacy ->
            val server = ServerUrl.parse(legacy)
            prefs.edit {
                if (server != null) putString(if (server.isHttps) KEY_EXTERNAL_URL else KEY_HOME_URL, server.base)
                remove(LEGACY_KEY_URL)
            }
        }
        // Token store of the deprecated EncryptedSharedPreferences; the token must be entered again.
        context.deleteSharedPreferences(LEGACY_SECRETS_FILE)
        // Arbitration settings that are no longer user-facing.
        prefs.edit { remove("arbitration_window_ms"); remove("arbitration_group") }
        context.deleteSharedPreferences("arbitration_peers")
    }

    companion object {
        const val DEFAULT_AUTO_CLOSE_SECONDS = 5
        const val DEFAULT_WAKE_THRESHOLD = 0.5f
        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_NIGHT_START = 23 * 60
        const val DEFAULT_NIGHT_END = 6 * 60

        private const val KEY_HOME_URL = "home_url"
        private const val KEY_EXTERNAL_URL = "external_url"
        private const val KEY_HOME_SSIDS = "home_ssids"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_PIPELINE = "pipeline_id"
        private const val KEY_AUTO_CLOSE = "auto_close_seconds"
        private const val KEY_CHIME = "chime_enabled"
        private const val KEY_WAKE_ENABLED = "wake_enabled"
        private const val KEY_WAKE_PAUSED = "wake_paused"
        private const val KEY_WAKE_MODEL = "wake_model"
        private const val KEY_WAKE_THRESHOLD = "wake_threshold"
        private const val KEY_WAKE_LOAD_FAILED = "wake_load_failed"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_ARBITRATION = "arbitration_enabled"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_HA_INSTANCE = "ha_instance_key"
        private const val KEY_SESSION_STYLE = "session_style"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_HA_VERSION = "ha_version"
        private const val KEY_ASSIST_LINK = "assist_link"
        private const val KEY_SATELLITE_KEY = "satellite_key"
        private const val KEY_SATELLITE_MAC = "satellite_mac"
        private const val KEY_OLD_VERSION_NOTICE = "old_version_notice_shown"
        private const val KEY_IDLE_NIGHT_START = "idle_night_start"
        private const val KEY_IDLE_NIGHT_END = "idle_night_end"
        private const val KEY_IDLE_HEBREW_DATE = "idle_hebrew_date"
        private const val KEY_IDLE_DAY_BRIGHTNESS = "idle_day_brightness"
        private const val KEY_IDLE_NIGHT_SCREEN_OFF = "idle_night_screen_off"
        private const val KEY_IDLE_AUTO_START = "idle_auto_start"
        private const val KEY_LOCAL_PLAYER = "local_player_enabled"
        private const val KEY_IDLE_MEDIA_LOCAL = "idle_media_player_local"
        private const val KEY_IDLE_WEATHER = "idle_weather_entity"
        private const val KEY_IDLE_MEDIA_PLAYER = "idle_media_player"
        private const val KEY_DEVICE_ALARMS = "device_alarms"
        private const val LEGACY_KEY_URL = "server_url"
        private const val LEGACY_SECRETS_FILE = "secrets"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
