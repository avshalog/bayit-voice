package com.avshy.bayitvoice.timer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.avshy.bayitvoice.R
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * Direct mode (SPEC section 10.1): an alarm or reminder asked for on this phone is set in the
 * phone's own clock app, as "Hey Google" does; a reminder is an alarm labelled with its text.
 * HA reaches the phone through an event during the conversation; this answers it.
 */
object PhoneAlarms {

    /** The event an HA script fires, and the one this phone answers with. */
    const val REQUEST_EVENT = "bayit_voice_alarm"
    const val ACK_EVENT = "bayit_voice_alarm_ack"

    /** Handles one request ([data] of [REQUEST_EVENT]) and returns the answer's event data. */
    fun handle(context: Context, data: JSONObject): JSONObject {
        val ack = JSONObject().put("request_id", data.optString("request_id")).put("device", "phone")
        return when (data.optString("action")) {
            "set" -> set(context, data, ack)
            // The clock app keeps them; the phone cannot read or cancel them by voice.
            else -> ack.put("ok", false).put("message", "On this phone, alarms are listed and cancelled in the clock app.")
        }
    }

    private fun set(context: Context, data: JSONObject, ack: JSONObject): JSONObject {
        val reminder = data.optString("kind") == "reminder"
        val text = data.optString("text").trim()
        val minutes = data.optInt("minutes", 0)
        val at = when {
            minutes > 0 -> LocalDateTime.now().plusMinutes(minutes.toLong()).plusSeconds(59).withSecond(0)
            else -> DeviceAlarms.nextOccurrence(data.optString("time"))?.let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            }
        } ?: return ack.put("ok", false).put("message", "Time must be HH:MM")
        val label = when {
            reminder -> context.getString(R.string.phone_reminder_label, text)
            else -> text
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, at.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "Set a clock-app alarm at %02d:%02d".format(at.hour, at.minute))
            ack.put("ok", true)
                .put("kind", if (reminder) "reminder" else "alarm")
                .put("time", "%02d:%02d".format(at.hour, at.minute))
                .put("date", at.toLocalDate().toString())
                .put("text", text)
                .put("where", "phone clock app")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No clock app handles alarms")
            ack.put("ok", false).put("message", "This phone has no clock app that accepts alarms.")
        } catch (e: SecurityException) {
            Log.w(TAG, "Clock app refused: ${e.message}")
            ack.put("ok", false).put("message", "The clock app refused the alarm.")
        }
    }

    private const val TAG = "PhoneAlarms"
}
