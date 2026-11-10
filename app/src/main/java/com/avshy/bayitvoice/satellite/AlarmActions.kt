package com.avshy.bayitvoice.satellite

import android.content.Context
import android.util.Log
import com.avshy.bayitvoice.satellite.proto.ApiMessage
import com.avshy.bayitvoice.satellite.proto.ExecuteService
import com.avshy.bayitvoice.satellite.proto.Outgoing
import com.avshy.bayitvoice.satellite.proto.ServiceArgType
import com.avshy.bayitvoice.satellite.proto.SupportsResponse
import com.avshy.bayitvoice.timer.AlarmKind
import com.avshy.bayitvoice.timer.DeviceAlarm
import com.avshy.bayitvoice.timer.DeviceAlarms
import org.json.JSONArray
import org.json.JSONObject

/**
 * The satellite's user-defined actions for alarms and reminders (SPEC section 10.1). HA registers
 * each as `esphome.<device name>_<action>` and runs it with `ExecuteServiceRequest`.
 */
class AlarmActions(context: Context, private val send: (ApiMessage) -> Boolean) {

    private val alarms = DeviceAlarms.get(context)

    private class Action(
        val name: String,
        val args: List<Pair<String, Int>>,
        val run: (List<String>, List<Int>) -> JSONObject,
    ) {
        val key: Int = "bayit.$name".hashCode()
    }

    private val actions = listOf(
        Action("set_alarm", listOf("time" to ServiceArgType.STRING, "label" to ServiceArgType.STRING)) { s, _ ->
            added(alarms.add(AlarmKind.ALARM, at(s[0]), s[1]))
        },
        Action("set_reminder", listOf("time" to ServiceArgType.STRING, "text" to ServiceArgType.STRING)) { s, _ ->
            added(alarms.add(AlarmKind.REMINDER, at(s[0]), s[1].ifBlank { throw IllegalArgumentException("A reminder needs a text") }))
        },
        Action("set_reminder_in", listOf("minutes" to ServiceArgType.INT, "text" to ServiceArgType.STRING)) { s, n ->
            val minutes = n[0]
            require(minutes in 1..MAX_MINUTES) { "Minutes must be between 1 and $MAX_MINUTES" }
            require(s[1].isNotBlank()) { "A reminder needs a text" }
            added(alarms.add(AlarmKind.REMINDER, System.currentTimeMillis() + minutes * 60_000L, s[1]))
        },
        Action("list_alarms", emptyList()) { _, _ ->
            JSONObject().put("alarms", JSONArray(alarms.alarms.value.map(DeviceAlarm::toJson)))
        },
        Action("cancel_alarm", listOf("which" to ServiceArgType.STRING, "kind" to ServiceArgType.STRING)) { s, _ ->
            val kind = when (s[1].trim().lowercase()) {
                "alarm" -> AlarmKind.ALARM
                "reminder" -> AlarmKind.REMINDER
                else -> null
            }
            val removed = alarms.cancel(s[0], kind)
            JSONObject().put("cancelled", JSONArray(removed.map(DeviceAlarm::toJson)))
        },
    )

    /** One `ListEntitiesServicesResponse` per action, sent while HA lists the device's entities. */
    fun listMessages(): List<ApiMessage> = actions.map { a ->
        val response = if (a.name == "list_alarms") SupportsResponse.ONLY else SupportsResponse.OPTIONAL
        Outgoing.listService(a.name, a.key, a.args, response)
    }

    fun execute(call: ExecuteService) {
        val action = actions.firstOrNull { it.key == call.key }
        val result = runCatching {
            requireNotNull(action) { "Unknown action" }
            val strings = action.args.mapIndexed { i, (_, type) -> if (type == ServiceArgType.STRING) call.args.getOrNull(i)?.string.orEmpty() else "" }
            val ints = action.args.mapIndexed { i, (_, type) -> if (type == ServiceArgType.INT) call.args.getOrNull(i)?.int ?: 0 else 0 }
            action.run(strings, ints)
        }
        result.exceptionOrNull()?.let { Log.w(TAG, "${action?.name ?: call.key} failed: ${it.message}") }
        if (call.callId != 0) {
            send(
                Outgoing.executeServiceResponse(
                    callId = call.callId,
                    success = result.isSuccess,
                    error = result.exceptionOrNull()?.message,
                    json = if (call.returnResponse) result.getOrNull()?.toString() else null,
                ),
            )
        }
    }

    private fun at(time: String): Long =
        DeviceAlarms.nextOccurrence(time) ?: throw IllegalArgumentException("Time must be HH:MM, got '$time'")

    private fun added(alarm: DeviceAlarm) = JSONObject().put("added", alarm.toJson())

    private companion object {
        const val TAG = "AlarmActions"
        const val MAX_MINUTES = 7 * 24 * 60
    }
}
