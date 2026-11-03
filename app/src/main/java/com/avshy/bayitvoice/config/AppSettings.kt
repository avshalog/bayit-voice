package com.avshy.bayitvoice.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.avshy.bayitvoice.wakeword.WakeWordModels

/** How the HA WebSocket is held between sessions (SPEC section 7.3). */
enum class ConnectionMode { KeepConnected, OnDemand }

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
    }

    companion object {
        const val DEFAULT_AUTO_CLOSE_SECONDS = 5
        const val DEFAULT_WAKE_THRESHOLD = 0.5f

        private const val KEY_HOME_URL = "home_url"
        private const val KEY_EXTERNAL_URL = "external_url"
        private const val KEY_HOME_SSIDS = "home_ssids"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_PIPELINE = "pipeline_id"
        private const val KEY_AUTO_CLOSE = "auto_close_seconds"
        private const val KEY_CHIME = "chime_enabled"
        private const val KEY_WAKE_ENABLED = "wake_enabled"
        private const val KEY_WAKE_MODEL = "wake_model"
        private const val KEY_WAKE_THRESHOLD = "wake_threshold"
        private const val KEY_WAKE_LOAD_FAILED = "wake_load_failed"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_HA_VERSION = "ha_version"
        private const val KEY_OLD_VERSION_NOTICE = "old_version_notice_shown"
        private const val LEGACY_KEY_URL = "server_url"
        private const val LEGACY_SECRETS_FILE = "secrets"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
