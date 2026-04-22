package com.stt.floater

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object SttClient {
    private val executor = Executors.newSingleThreadExecutor()

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
        onResult: (Boolean, String) -> Unit,
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
                    val text = JSONObject(body).optString("text", "")
                    onResult(true, text.ifBlank { "(empty)" })
                } else {
                    val err = try { JSONObject(body).optString("error", body) } catch (_: Exception) { body }
                    onResult(false, "http $code: $err")
                }
            } catch (e: Exception) {
                Log.e("SttClient", "sendAudio failed", e)
                onResult(false, e.message ?: "error")
            } finally {
                conn?.disconnect()
            }
        }
    }
}
