package com.avshy.bayitvoice.satellite

import android.util.Log
import com.avshy.bayitvoice.satellite.proto.ActionResponse
import com.avshy.bayitvoice.satellite.proto.ApiMessage
import com.avshy.bayitvoice.satellite.proto.HomeAssistantState
import com.avshy.bayitvoice.satellite.proto.Outgoing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home Assistant data over the ESPHome API (SPEC section 11): the states of chosen entities,
 * and actions whose response comes back as JSON. HA sends nothing until it has asked for the
 * device's state subscriptions, which it does on every connection.
 */
class HaLink(private val send: (ApiMessage) -> Boolean) {

    /** (entity id, attribute); an empty attribute means the entity's state itself. */
    data class Key(val entityId: String, val attribute: String = "")

    private val _states = MutableStateFlow<Map<Key, String>>(emptyMap())

    /** Latest values HA sent for the subscribed keys. Empty while HA is not connected. */
    val states: StateFlow<Map<Key, String>> = _states.asStateFlow()

    private val wantedBy = ConcurrentHashMap<String, Set<Key>>()
    private val wanted: Set<Key> get() = wantedBy.values.flatten().toSet()
    @Volatile private var subscribed: Set<Key> = emptySet()
    @Volatile private var haAsked = false

    private val nextCallId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ActionResponse>>()

    /**
     * The keys [owner] wants (weather, music, ...), replacing what it wanted before; new ones are
     * requested at once if HA is connected. HA cannot unsubscribe, so dropped keys stay until it reconnects.
     */
    @Synchronized
    fun want(owner: String, keys: Set<Key>) {
        wantedBy[owner] = keys
        if (haAsked) sendSubscriptions()
    }

    /** HA asked for the subscriptions (`SubscribeHomeAssistantStatesRequest`). */
    @Synchronized
    fun onStatesRequested() {
        haAsked = true
        subscribed = emptySet()
        sendSubscriptions()
    }

    fun onState(state: HomeAssistantState) {
        val key = Key(state.entityId, state.attribute)
        _states.value = _states.value + (key to state.state)
    }

    fun onActionResponse(response: ActionResponse) {
        pending.remove(response.callId)?.complete(response)
    }

    fun onDisconnected() {
        haAsked = false
        subscribed = emptySet()
        _states.value = emptyMap()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    /**
     * Runs `domain.service` in HA and returns its response, or null when HA did not answer in
     * time: actions are refused unless "Allow the device to perform Home Assistant actions" is
     * on for this device in HA.
     */
    suspend fun call(service: String, data: Map<String, String>, timeoutMs: Long = ACTION_TIMEOUT_MS): ActionResponse? {
        if (!haAsked) return null
        val id = nextCallId.getAndIncrement()
        val result = CompletableDeferred<ActionResponse>()
        pending[id] = result
        if (!send(Outgoing.action(service, data, id, wantsResponse = true))) {
            pending.remove(id)
            return null
        }
        return withTimeoutOrNull(timeoutMs) { result.await() }.also { pending.remove(id) }
    }

    /**
     * Runs `domain.service` in HA without waiting for an answer, for actions that return nothing
     * (asking for a response from those makes HA refuse them). False when HA is not connected.
     */
    fun fire(service: String, data: Map<String, String>): Boolean =
        haAsked && send(Outgoing.action(service, data, callId = 0, wantsResponse = false))

    private fun sendSubscriptions() {
        val toSend = wanted - subscribed
        for (key in toSend) {
            if (send(Outgoing.subscribeState(key.entityId, key.attribute))) subscribed = subscribed + key
        }
        if (toSend.isNotEmpty()) Log.i(TAG, "Subscribed to ${toSend.size} HA states")
    }

    private companion object {
        const val TAG = "HaLink"
        const val ACTION_TIMEOUT_MS = 15_000L
    }
}
