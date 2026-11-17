package app.ormem.orson

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** Minimal client for the Ormem API (https://ormem.app/docs.html). */
class Ormem(private val key: String, private val owner: String) {

    class Turn(val role: String, val text: String)
    class Reply(val intent: String, val reply: String)

    /**
     * Hands one spoken turn to Ormem, which saves, answers or asks to repeat.
     * Retries server and network failures with the same key, so nothing is saved twice.
     */
    fun converse(text: String, speaker: String, sessionId: String, recent: List<Turn>): Reply {
        val body = JSONObject()
            .put("owner", owner)
            .put("speaker", speaker)
            .put("text", text.take(2000))
            .put("idempotency_key", UUID.randomUUID().toString())
            .put("session_id", sessionId)
            .put("device", JSONObject()
                .put("now", OffsetDateTime.now().toString())
                .put("timezone", ZoneId.systemDefault().id)
                .put("locale", Locale.getDefault().toLanguageTag()))
            .put("recent_turns", JSONArray().apply {
                recent.takeLast(MAX_TURNS).forEach { put(JSONObject().put("role", it.role).put("text", it.text)) }
            })
        var attempt = 0
        while (true) {
            try {
                val res = post("/v1/converse", body)
                return Reply(res.optString("intent"), res.optString("reply"))
            } catch (e: Exception) {
                val retry = when {
                    e is OrmemError && e.code >= 500 -> attempt < 2
                    e is OrmemError && e.code == 429 && "minute" in (e.message ?: "") -> attempt < 1
                    e is OrmemError -> false
                    else -> attempt < 2 // network
                }
                if (!retry) throw e
                Thread.sleep(if (e is OrmemError && e.code == 429) 4_000 else 1_500)
                attempt++
            }
        }
    }

    /** Throws if the key is missing, wrong or cancelled. */
    fun check() {
        request("GET", "/v1/key", null)
    }

    /** This month's use against the plan's limits. */
    fun usage(): String {
        val res = request("GET", "/v1/my/usage", null)
        val limits = res.optJSONObject("limits")
        return "${res.optInt("messages")} of ${limits?.optInt("messages")} messages, " +
            "${res.optInt("questions")} of ${limits?.optInt("questions")} questions this month"
    }

    /** Latest memories as (id, text), newest first. */
    fun recent(limit: Int = 10): List<Pair<String, String>> {
        val text = raw("GET", "/v1/memories?owner=${encode(owner)}&limit=$limit", null)
        val list = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("memories")
        return (0 until (list?.length() ?: 0)).map { i ->
            list!!.getJSONObject(i).let { it.optString("id") to it.optString("body") }
        }
    }

    /** Permanently erases one memory. */
    fun erase(id: String) {
        raw("DELETE", "/v1/memories/${encode(id)}?owner=${encode(owner)}&mode=erase", null)
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun post(path: String, body: JSONObject) = request("POST", path, body)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val text = raw(method, path, body)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun raw(method: String, path: String, body: JSONObject?): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw OrmemError(code, detail?.ifBlank { null } ?: "HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    class OrmemError(val code: Int, message: String) : Exception(message)

    companion object {
        const val BASE = "https://app.ormem.app"
        private const val MAX_TURNS = 12
    }
}
