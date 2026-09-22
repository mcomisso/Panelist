package com.mcsoftware.paneldash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class Ping { OK, UNAUTHORIZED, UNREACHABLE }

data class EntityState(
    val state: String = "",
    val friendly: String = "",
    val attrs: Map<String, Any?> = emptyMap(),
)

class HaClient(private val baseUrl: String, private val token: String) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private fun apiUrl(path: String): String = baseUrl.trimEnd('/') + path

    suspend fun ping(): Ping = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(apiUrl("/api/"))
                .header("Authorization", "Bearer $token")
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 200 -> Ping.OK
                    resp.code == 401 || resp.code == 403 -> Ping.UNAUTHORIZED
                    else -> Ping.OK
                }
            }
        } catch (e: Exception) {
            Ping.UNREACHABLE
        }
    }

    suspend fun getState(entityId: String): EntityState? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(apiUrl("/api/states/" + entityId))
                .header("Authorization", "Bearer $token")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val obj = JSONObject(body)
                val attrsObj = obj.optJSONObject("attributes")
                val attrs = LinkedHashMap<String, Any?>()
                if (attrsObj != null) {
                    val keys = attrsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        attrs[k] = attrsObj.opt(k)
                    }
                }
                EntityState(
                    state = obj.optString("state", ""),
                    friendly = attrsObj?.optString("friendly_name", entityId) ?: entityId,
                    attrs = attrs,
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetch one JPEG frame from HA's camera proxy, resized server-side
     * (returns ~800x600 / 45 KB for a 480 request — light on the panel's SoC).
     */
    suspend fun getCameraSnapshot(cameraId: String, size: Int = 480): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(apiUrl("/api/camera_proxy/" + cameraId + "?width=" + size + "&height=" + size))
                    .header("Authorization", "Bearer $token")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.bytes()
                }
            } catch (e: Exception) {
                null
            }
        }

    suspend fun getStates(ids: List<String>): Map<String, EntityState> = coroutineScope {
        ids.distinct()
            .map { id -> async { id to getState(id) } }
            .mapNotNull { d ->
                val pair = d.await()
                pair.second?.let { pair.first to it }
            }
            .toMap()
    }

    suspend fun callService(service: String, entityId: String?, data: Map<String, Any?>?): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val parts = service.split(".", limit = 2)
                if (parts.size != 2) return@withContext false
                val sb = StringBuilder("{")
                var first = true
                fun add(key: String, value: Any?) {
                    if (value == null) return
                    if (!first) sb.append(',')
                    first = false
                    sb.append(jsonString(key)).append(':').append(jsonValue(value))
                }
                add("entity_id", entityId)
                data?.forEach { (k, v) -> add(k, v) }
                sb.append('}')
                val req = Request.Builder()
                    .url(apiUrl("/api/services/" + parts[0] + "/" + parts[1]))
                    .header("Authorization", "Bearer $token")
                    .post(sb.toString().toRequestBody(jsonMedia))
                    .build()
                client.newCall(req).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> jsonString(v)
        is Boolean, is Int, is Long, is Double, is Float -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> jsonString(k.toString()) + ":" + jsonValue(x) }
        is List<*> -> v.joinToString(",", "[", "]") { jsonValue(it) }
        else -> jsonString(v.toString())
    }
}
