package com.avshy.bayitvoice.arbitration

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.delay
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.session.Arbitration
import com.avshy.bayitvoice.session.WakeDetection

/**
 * Arbitration for one wake-word session: turns the session's measurements into a claim and
 * exchanges it with the other devices (SPEC section 6.2).
 */
class SessionArbitration private constructor(
    context: Context,
    private val settings: AppSettings,
    private val groupKey: ByteArray,
    private val detection: WakeDetection,
) : Arbitration {

    private val app = context.applicationContext
    private val arbiter = PeerArbiter(app)

    override suspend fun winnerElsewhere(commandSnrDb: Float?): String? {
        val claim = Claim(settings.deviceId, deviceName(app), proximity(detection, commandSnrDb), System.currentTimeMillis())
        return arbiter.claim(groupKey, claim, PeerArbiter.DEFAULT_WINDOW_MS)?.deviceName
    }

    companion object {
        /** dB of proximity per unit of detection confidence: 0.5 → 1.0 confidence adds 5 dB. */
        private const val CONFIDENCE_WEIGHT = 10f

        /** Beats any real proximity score: the wake word belongs to this device. */
        private const val OWNED_SCORE = 1_000f

        /**
         * Sessions decide this long after their own detection ([com.avshy.bayitvoice.session.AssistSession]);
         * an owned claim is sent from a little before that until a little after.
         */
        private const val OWNED_CLAIM_FROM_MS = 1_200L
        private const val OWNED_CLAIM_WINDOW_MS = 800L

        /**
         * Claims a wake word this device used for itself (it silenced a ringing timer), so other
         * devices that heard it yield instead of opening a session. Does nothing when not arbitrating.
         */
        suspend fun claimOwned(context: Context, detection: WakeDetection) {
            val settings = AppSettings.get(context)
            val groupId = settings.arbitrationGroupId
            if (!settings.arbitrationEnabled || groupId == null) return
            delay((detection.detectedAtMs + OWNED_CLAIM_FROM_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            val claim = Claim(settings.deviceId, deviceName(context), OWNED_SCORE, System.currentTimeMillis())
            PeerArbiter(context).claim(Claim.groupKey(groupId), claim, OWNED_CLAIM_WINDOW_MS)
        }

        /** Null when this session should not arbitrate: switched off, no group, or not a wake word. */
        fun forSession(context: Context, detection: WakeDetection?): SessionArbitration? {
            val settings = AppSettings.get(context)
            val groupId = settings.arbitrationGroupId
            if (detection == null || !settings.arbitrationEnabled || groupId == null) return null
            return SessionArbitration(context, settings, Claim.groupKey(groupId), detection)
        }

        /**
         * The claim's score: the wake word's and the command's level above the noise floor,
         * averaged, plus a little for detection confidence to break near-ties.
         */
        fun proximity(detection: WakeDetection, commandSnrDb: Float?): Float {
            val level = if (commandSnrDb == null) detection.snrDb else (detection.snrDb + commandSnrDb) / 2
            return level + CONFIDENCE_WEIGHT * detection.score
        }

        /** The name set in the app, else the one the user gave the device in system settings, else its model. */
        fun deviceName(context: Context): String =
            AppSettings.get(context).deviceName ?: systemDeviceName(context)

        fun systemDeviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}
