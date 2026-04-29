package com.stt.floater

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object SttClient {
    private val executor = Executors.newSingleThreadExecutor()

    /** Query the server for all attached tmux session names (for the
     *  tap-to-pick menu under the mic). Returns empty list on error. */
    fun fetchSessions(baseUrl: String, token: String, onResult: (List<String>) -> Unit) {
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(normalize(baseUrl) + "/sessions")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("X-Token", token)
                    connectTimeout = 3000
                    readTimeout = 4000
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val arr = JSONObject(body).optJSONArray("sessions")
                    val list = mutableListOf<String>()
                    if (arr != null) for (i in 0 until arr.length()) list.add(arr.getString(i))
                    onResult(list)
                } else {
                    onResult(emptyList())
                }
            } catch (e: Exception) {
                Log.w("SttClient", "fetchSessions: ${e.message}")
                onResult(emptyList())
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** Query the server for the tmux session that would be auto-routed right
     *  now (most-recently-attached). Returns empty string if none or on error. */
    fun fetchActiveSession(baseUrl: String, token: String, onResult: (String) -> Unit) {
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(normalize(baseUrl) + "/active_session")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("X-Token", token)
                    connectTimeout = 3000
                    readTimeout = 4000
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    onResult(JSONObject(body).optString("session", ""))
                } else {
                    onResult("")
                }
            } catch (e: Exception) {
                Log.w("SttClient", "fetchActiveSession: ${e.message}")
                onResult("")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun normalize(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://"))
            trimmed
        else
            "http://$trimmed"
    }

    fun send(
        baseUrl: String,
        token: String,
        text: String,
        submit: Boolean,
        onResult: (Boolean, String) -> Unit,
    ) {
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(normalize(baseUrl) + "/send")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val body = JSONObject()
                    .put("text", text)
                    .put("submit", submit)
                    .put("token", token)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val ok = code in 200..299
                onResult(ok, if (ok) "sent" else "http $code")
            } catch (e: Exception) {
                Log.e("SttClient", "send failed", e)
                onResult(false, e.message ?: "error")
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun sendAudio(
        baseUrl: String,
        token: String,
        wav: ByteArray,
        submit: Boolean,
        pasteOnPc: Boolean,
        tmuxTarget: String,
        transcribeOnly: Boolean = false,
        onResult: (Boolean, String, String) -> Unit,
    ) {
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(normalize(baseUrl) + "/transcribe_and_send")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "audio/wav")
                    setRequestProperty("X-Token", token)
                    setRequestProperty("X-Submit", submit.toString())
                    setRequestProperty("X-Paste", pasteOnPc.toString())
                    if (tmuxTarget.isNotEmpty())
                        setRequestProperty("X-Tmux-Session", tmuxTarget)
                    if (transcribeOnly)
                        setRequestProperty("X-Transcribe-Only", "true")
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 120000
                    setFixedLengthStreamingMode(wav.size)
                }
                conn.outputStream.use { it.write(wav) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val json = JSONObject(body)
                    val text = json.optString("text", "")
                    // Server returns `tmux_target` only when send-keys actually
                    // routed; absent means it fell through to PC paste, or
                    // (with transcribe-only) that no routing was attempted.
                    val routed = json.optString("tmux_target", "")
                    onResult(true, text.ifBlank { "(empty)" }, routed)
                } else {
                    val err = try { JSONObject(body).optString("error", body) } catch (_: Exception) { body }
                    onResult(false, "http $code: $err", "")
                }
            } catch (e: Exception) {
                Log.e("SttClient", "sendAudio failed", e)
                onResult(false, e.message ?: "error", "")
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** Deliver a previously-transcribed text via /send. Used by the confirm
     *  flow: audio is transcribed first (with X-Transcribe-Only), the user
     *  taps to confirm, then this delivers the routing. */
    fun deliverText(
        baseUrl: String,
        token: String,
        text: String,
        tmuxTarget: String,
        pasteOnPc: Boolean,
        submit: Boolean,
        onResult: (Boolean, String, String) -> Unit,
    ) {
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(normalize(baseUrl) + "/send")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 30000
                }
                val body = JSONObject()
                    .put("token", token)
                    .put("text", text)
                    .put("tmux_target", tmuxTarget)
                    .put("paste", pasteOnPc)
                    .put("submit", submit)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val respBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val json = JSONObject(respBody)
                    val routed = json.optString("tmux_target", "")
                    onResult(true, text, routed)
                } else {
                    val err = try { JSONObject(respBody).optString("error", respBody) } catch (_: Exception) { respBody }
                    onResult(false, "http $code: $err", "")
                }
            } catch (e: Exception) {
                Log.e("SttClient", "deliverText failed", e)
                onResult(false, e.message ?: "error", "")
            } finally {
                conn?.disconnect()
            }
        }
    }
}
