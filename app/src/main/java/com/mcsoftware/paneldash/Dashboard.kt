package com.mcsoftware.paneldash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Actions {
    fun serviceFor(action: String?): String? = when (action) {
        null, "", "toggle" -> "homeassistant.toggle"
        "turn_on", "on" -> "homeassistant.turn_on"
        "turn_off", "off" -> "homeassistant.turn_off"
        "activate" -> "scene.turn_on"
        else -> null
    }

    fun isOn(state: String?): Boolean {
        val v = state?.trim()?.lowercase() ?: return false
        return v.isNotEmpty() && v !in setOf(
            "off", "closed", "unavailable", "unknown", "none", "idle", "standby", "0",
        )
    }
}

class Dashboard {
    var config by mutableStateOf<PanelConfig?>(null)
    var configPath by mutableStateOf("")
    var status by mutableStateOf("")
    var currentPage by mutableStateOf(0)
    var streaming by mutableStateOf(false)
    var client: HaClient? = null
    val states = mutableStateMapOf<String, EntityState>()

    // Doorbell alert: raised when the configured ring entity transitions to "on".
    // The UI shows a full-screen live-camera overlay; nonce extends the timer on a
    // second ring while already visible.
    var alertVisible by mutableStateOf(false)
    var alertNonce by mutableStateOf(0)
    private var primedForAlerts = false

    // Camera peek: full-screen live view of this camera entity (null = closed).
    var cameraVisible by mutableStateOf<String?>(null)
    var cameraTitle by mutableStateOf("Camera")

    // Idle return: wall panels drift back to the first page after a stretch with
    // no touches, so the next person finds the home screen. MainActivity polls
    // this timestamp; every touch anywhere bumps it (see the root pointerInput).
    var lastInteractionAt by mutableStateOf(System.currentTimeMillis())
    fun bumpInteraction() { lastInteractionAt = System.currentTimeMillis() }

    /** Called once after the first full snapshot so the current state can't "ring". */
    fun markPrimed() { primedForAlerts = true }

    private fun maybeAlert(id: String, old: String?, new: String?) {
        if (!primedForAlerts || new == null) return
        val db = config?.doorbell ?: return
        if (id != db.entity) return
        val ring = when {
            new == "on" && old != "on" -> true
            id.startsWith("event.") && new != "unknown" && new != old -> true
            else -> false
        }
        if (ring) {
            alertVisible = true
            alertNonce++
            ProximityStore.bump()   // wake the panel so the popup is visible
        }
    }

    fun entityIds(): List<String> {
        val out = LinkedHashSet<String>()
        config?.header?.let { collect(it, out) }
        config?.pages?.forEach { collect(it.node, out) }
        // The doorbell ring entity is not part of any page tree; subscribe to it
        // explicitly so the popup can react to ring events.
        config?.doorbell?.entity?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out.toList()
    }

    private fun collect(node: NodeCfg, into: MutableSet<String>) {
        node.entity?.let { into.add(it) }
        node.subEntity?.let { into.add(it) }
        node.children.forEach { collect(it, into) }
    }

    /** Apply a full state snapshot pushed by the WebSocket API (first message). */
    fun applyFull(id: String, st: EntityState) {
        maybeAlert(id, states[id]?.state, st.state)
        states[id] = st
    }

    /**
     * Apply a compact WebSocket diff for one entity. Diffs only carry what
     * changed, so merge them onto the known state.
     */
    fun applyDelta(
        id: String,
        newState: String?,
        attrAdd: Map<String, Any?>?,
        attrRemoved: List<String>,
    ) {
        val cur = states[id]
        maybeAlert(id, cur?.state, newState)
        if (cur == null) {
            if (newState != null || attrAdd != null) {
                states[id] = EntityState(state = newState ?: "", attrs = attrAdd ?: emptyMap())
            }
            return
        }
        var attrs = cur.attrs
        if (attrAdd != null || attrRemoved.isNotEmpty()) {
            val m = LinkedHashMap(attrs)
            attrAdd?.forEach { (k, v) -> m[k] = v }
            attrRemoved.forEach { m.remove(it) }
            attrs = m
        }
        states[id] = cur.copy(
            state = newState ?: cur.state,
            friendly = (attrs["friendly_name"] as? String) ?: cur.friendly,
            attrs = attrs,
        )
    }

    fun tap(scope: CoroutineScope, node: NodeCfg) {
        ProximityStore.bump()
        bumpInteraction()
        // `type: camera` tiles open the full-screen live peek instead of calling HA.
        if (node.type == "camera") {
            node.entity?.takeIf { it.isNotBlank() }?.let {
                cameraTitle = node.text ?: "Camera"
                cameraVisible = it
            }
            return
        }
        val entity = node.entity
        val action = node.action
        if (action == "none") return
        val svc = (if (action == "service") {
            node.service?.takeIf { it.contains(".") }
        } else {
            Actions.serviceFor(action)
        }) ?: return
        // Entity-based actions need a target; raw service calls may target nothing
        // (e.g. script./scene. services that accept an empty body).
        if (entity == null && action != "service") return

        // Optimistic flip so the panel feels instant on a slow network.
        if (entity != null) {
            when (svc) {
                "homeassistant.toggle" -> states[entity]?.let {
                    states[entity] = it.copy(state = if (Actions.isOn(it.state)) "off" else "on")
                }
                "homeassistant.turn_on" -> states[entity]?.let { states[entity] = it.copy(state = "on") }
                "homeassistant.turn_off" -> states[entity]?.let { states[entity] = it.copy(state = "off") }
            }
        }

        val c = client ?: return
        scope.launch {
            val ok = c.callService(svc, entity, node.data)
            // Streaming: Home Assistant pushes the new state back within ~100 ms,
            // so the delayed REST refetch is only needed in fallback mode - or when
            // the call itself failed and no push will ever arrive.
            if (entity != null && (!streaming || !ok)) {
                delay(700)
                c.getState(entity)?.let { states[entity] = it }
            }
        }
    }
}
