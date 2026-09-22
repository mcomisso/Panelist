package com.mcsoftware.paneldash

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "PanelDashStream"

/** How a stream session ended (drives the reconnect loop). */
sealed class StreamEnd {
    /** Home Assistant rejected the token (or it is missing/blank). */
    object AuthFailed : StreamEnd()

    /** Connection dropped or failed (network). */
    object Disconnected : StreamEnd()
}

private sealed class Ev {
    class Message(val text: String) : Ev()
    class Closed(val code: Int) : Ev()
    class Failure(val error: Throwable?) : Ev()
}

private val streamClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // No read timeout: the socket sits idle between pushes; OkHttp's 30s pings
        // detect a dead link and fail the call, which triggers a reconnect.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
}

/**
 * Connect to Home Assistant's WebSocket API, authenticate, subscribe to every entity
 * the panel uses, and pump pushed updates into [dash] until the socket ends.
 *
 * This replaces REST polling: one long-lived connection instead of a batch of HTTP
 * requests every poll_seconds, and changes (including attribute diffs such as the
 * blind's current_position) arrive within ~100ms of happening.
 *
 * Returns when the socket ends; callers own the reconnect/backoff policy.
 */
suspend fun runStreamOnce(url: String, token: String, dash: Dashboard): StreamEnd {
    val wsUrl = (when {
        url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
        url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
        else -> "ws://$url"
    }).trimEnd('/') + "/api/websocket"

    val events = Channel<Ev>(Channel.UNLIMITED)
    val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(Ev.Message(text))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(Ev.Closed(code))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "websocket failure: " + (t.message ?: t.javaClass.simpleName))
            events.trySend(Ev.Failure(t))
        }
    }
    val ws = streamClient.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    var end: StreamEnd = StreamEnd.Disconnected
    var authed = false
    try {
        while (true) {
            // The auth handshake gets a hard 15s budget; afterwards the 30s ping
            // keepalive is responsible for noticing a dead link.
            val ev = if (!authed) {
                withTimeoutOrNull(15_000) { events.receiveCatching().getOrNull() }
            } else {
                events.receiveCatching().getOrNull()
            }
            if (ev == null) break
            when (ev) {
                is Ev.Failure -> {
                    end = StreamEnd.Disconnected
                    break
                }

                is Ev.Closed -> {
                    end = StreamEnd.Disconnected
                    break
                }

                is Ev.Message -> {
                    val msg = try {
                        JSONObject(ev.text)
                    } catch (e: Exception) {
                        continue
                    }
                    when (msg.optString("type")) {
                        "auth_required" ->
                            ws.send(
                                JSONObject().put("type", "auth")
                                    .put("access_token", token).toString(),
                            )

                        "auth_invalid" -> {
                            end = StreamEnd.AuthFailed
                            break
                        }

                        "auth_ok" -> {
                            authed = true
                            dash.streaming = true
                            dash.status = ""
                            val ids = dash.entityIds()
                            if (ids.isNotEmpty()) {
                                ws.send(
                                    JSONObject()
                                        .put("id", 1)
                                        .put("type", "subscribe_entities")
                                        .put("entity_ids", JSONArray(ids))
                                        .toString(),
                                )
                                Log.i(TAG, "stream connected; subscribed to " + ids.size + " entities")
                            } else {
                                Log.i(TAG, "stream connected; config has no entities")
                            }
                        }

                        "event" -> applyEvent(msg, dash)

                        "result" -> if (!msg.optBoolean("success", true)) {
                            Log.w(TAG, "subscribe failed: " + msg.opt("error"))
                        }
                    }
                }
            }
        }
    } finally {
        dash.streaming = false
        ws.cancel()
    }
    return end
}

/**
 * Apply one `event` message: "a" carries a full snapshot (first message after
 * subscribe), "c" carries compact diffs of the form {entity: {"+"/"-": patch}}.
 */
private fun applyEvent(msg: JSONObject, dash: Dashboard) {
    val event = msg.optJSONObject("event") ?: return

    event.optJSONObject("a")?.let { full ->
        val keys = full.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val st = full.optJSONObject(id) ?: continue
            dash.applyFull(id, stateFrom(st, id))
        }
        dash.markPrimed()
    }

    event.optJSONObject("c")?.let { diff ->
        val keys = diff.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val d = diff.optJSONObject(id) ?: continue
            val plus = d.optJSONObject("+")
            val minus = d.optJSONObject("-")
            val newState = plus?.optString("s")?.ifEmpty { null }
            val attrAdd = plus?.optJSONObject("a")?.let { objToMap(it) }
            val removes = mutableListOf<String>()
            minus?.optJSONObject("a")?.let { obj ->
                val ks = obj.keys()
                while (ks.hasNext()) removes.add(ks.next())
            }
            dash.applyDelta(id, newState, attrAdd, removes)
        }
    }
}

private fun stateFrom(st: JSONObject, id: String): EntityState {
    val attrsObj = st.optJSONObject("a")
    val attrs = attrsObj?.let { objToMap(it) } ?: emptyMap()
    return EntityState(
        state = st.optString("s", ""),
        friendly = (attrs["friendly_name"] as? String) ?: id,
        attrs = attrs,
    )
}

private fun objToMap(o: JSONObject): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        out[k] = o.opt(k)
    }
    return out
}
