package com.avshy.bayitvoice.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * All user configuration. Non-secret values live in plain preferences;
 * the access token lives in encrypted preferences and is never logged.
 */
class AppSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val secrets: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var serverUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit { putString(KEY_URL, value) }

    var accessToken: String?
        get() = secrets.getString(KEY_TOKEN, null)
        set(value) = secrets.edit { putString(KEY_TOKEN, value) }

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

    /** HA version from the last successful `auth_ok`. */
    var lastHaVersion: String?
        get() = prefs.getString(KEY_HA_VERSION, null)
        set(value) = prefs.edit { putString(KEY_HA_VERSION, value) }

    var oldVersionNoticeShown: Boolean
        get() = prefs.getBoolean(KEY_OLD_VERSION_NOTICE, false)
        set(value) = prefs.edit { putBoolean(KEY_OLD_VERSION_NOTICE, value) }

    val server: ServerUrl? get() = serverUrl?.let(ServerUrl::parse)

    val isConfigured: Boolean get() = server != null && !accessToken.isNullOrEmpty()

    fun signOut() {
        secrets.edit { remove(KEY_TOKEN) }
        prefs.edit { remove(KEY_HA_VERSION) }
    }

    companion object {
        const val DEFAULT_AUTO_CLOSE_SECONDS = 5

        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_PIPELINE = "pipeline_id"
        private const val KEY_AUTO_CLOSE = "auto_close_seconds"
        private const val KEY_CHIME = "chime_enabled"
        private const val KEY_HA_VERSION = "ha_version"
        private const val KEY_OLD_VERSION_NOTICE = "old_version_notice_shown"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
