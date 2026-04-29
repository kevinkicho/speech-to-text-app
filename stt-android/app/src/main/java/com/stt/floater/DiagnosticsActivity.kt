package com.stt.floater

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.stt.floater.databinding.ActivityDiagnosticsBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Self-diagnose screen. Runs phone-local checks (URL, token, perms, can-reach
 * server) plus calls the server's /diagnose endpoint and combines everything
 * into one PASS/WARN/FAIL report.
 */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var b: ActivityDiagnosticsBinding
    private lateinit var prefs: Prefs

    private data class Row(val status: String, val name: String, val detail: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        b.runButton.setOnClickListener { runDiagnostics() }
        runDiagnostics()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun runDiagnostics() {
        b.report.text = "Running…"
        b.runButton.isEnabled = false
        thread(name = "diag") {
            val rows = mutableListOf<Row>()

            // Phone-local checks (no network).
            rows += rowFromPair("config: server URL set",
                if (prefs.serverUrl.isNotBlank()) "OK" else "FAIL",
                prefs.serverUrl.ifBlank { "missing — set it in main screen" })

            rows += rowFromPair("config: token customized",
                if (prefs.token != "change-me") "OK" else "WARN",
                if (prefs.token == "change-me") "default token; fine for personal Tailnet" else "non-default")

            rows += permRow("permission: RECORD_AUDIO", Manifest.permission.RECORD_AUDIO)
            rows += run {
                val ok = Settings.canDrawOverlays(this)
                Row(if (ok) "OK" else "FAIL", "permission: draw over apps",
                    if (ok) "granted" else "NOT granted — bubble can't display")
            }
            rows += accessibilityRow()
            rows += bubbleServiceRow()

            // WAV queue (any pending retries from past failures).
            val q = WavQueue(this).size()
            rows += Row(if (q == 0) "OK" else "WARN", "wav queue",
                if (q == 0) "empty" else "$q pending — will retry on next mic tap")

            // Network: bare reachability + auth.
            val (healthOk, healthDetail) = ping("/health", auth = false)
            rows += Row(if (healthOk) "OK" else "FAIL", "network: server /health", healthDetail)

            if (healthOk) {
                val (authOk, authDetail) = ping("/sessions", auth = true)
                rows += Row(if (authOk) "OK" else "FAIL", "auth: token accepted by server", authDetail)
                // Server-side checks via /diagnose.
                rows += fetchServerDiagnose()
            } else {
                rows += Row("WARN", "server-side checks", "skipped (server unreachable)")
            }

            val rendered = render(rows)
            runOnUiThread {
                b.report.text = rendered
                b.runButton.isEnabled = true
            }
        }
    }

    private fun rowFromPair(name: String, status: String, detail: String) = Row(status, name, detail)

    private fun permRow(name: String, perm: String): Row {
        val ok = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        return Row(if (ok) "OK" else "FAIL", name,
            if (ok) "granted" else "NOT granted — re-open main screen to request")
    }

    private fun accessibilityRow(): Row {
        val expected = "$packageName/${SttAccessibilityService::class.java.name}"
        val list = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: ""
        val enabled = list.split(":").any { it.equals(expected, ignoreCase = true) }
        val needs = prefs.clipboardMode && prefs.clipboardAutoEnter
        return when {
            enabled -> Row("OK", "accessibility service", "enabled")
            needs   -> Row("WARN", "accessibility service",
                "OFF — clipboard auto-paste-Enter won't work until you re-enable")
            else    -> Row("OK", "accessibility service", "disabled (not needed for current settings)")
        }
    }

    @Suppress("DEPRECATION")
    private fun bubbleServiceRow(): Row {
        // getRunningServices is deprecated for cross-app inspection but still
        // works for queries about your own package.
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val running = am.getRunningServices(Int.MAX_VALUE).orEmpty()
        val ours = running.any { it.service.className == OverlayService::class.java.name }
        return if (ours)
            Row("OK", "bubble service running", "OverlayService alive")
        else
            Row("WARN", "bubble service running", "not running — open main screen and tap Start")
    }

    private fun ping(path: String, auth: Boolean): Pair<Boolean, String> {
        val u = prefs.serverUrl
        if (u.isBlank()) return false to "no URL"
        val full = (if (u.startsWith("http")) u else "http://$u") + path
        return try {
            val conn = (URL(full).openConnection() as HttpURLConnection).apply {
                if (auth) setRequestProperty("X-Token", prefs.token)
                connectTimeout = 5000
                readTimeout = 8000
            }
            val code = conn.responseCode
            conn.disconnect()
            when {
                code in 200..299 -> true to "$full -> $code"
                code == 401 -> false to "$full -> 401 (token rejected)"
                code == 403 -> false to "$full -> 403 (origin not on Tailnet?)"
                else -> false to "$full -> $code"
            }
        } catch (e: Exception) {
            false to "$full -> ${e.javaClass.simpleName}: ${e.message ?: "error"}"
        }
    }

    private fun fetchServerDiagnose(): List<Row> {
        val u = prefs.serverUrl
        if (u.isBlank()) return listOf(Row("FAIL", "server diagnose", "no URL"))
        val full = (if (u.startsWith("http")) u else "http://$u") + "/diagnose"
        return try {
            val conn = (URL(full).openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-Token", prefs.token)
                connectTimeout = 5000
                readTimeout = 20000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return listOf(Row("FAIL", "server diagnose", "http $code"))
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONObject(body).optJSONArray("checks") ?: return emptyList()
            val out = mutableListOf<Row>()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                out += Row(
                    c.optString("status", "WARN"),
                    "server: " + c.optString("name", ""),
                    c.optString("detail", ""),
                )
            }
            out
        } catch (e: Exception) {
            Log.w("Diag", "diagnose fetch failed", e)
            listOf(Row("FAIL", "server diagnose", e.message ?: "error"))
        }
    }

    /** Build a colorized monospace report. Keeps it greppable + selectable. */
    private fun render(rows: List<Row>): CharSequence {
        val sb = SpannableStringBuilder()
        for (r in rows) {
            val tag = "[${r.status}]"
            val start = sb.length
            sb.append(tag.padEnd(7))
            val color = when (r.status) {
                "OK"   -> 0xFF2E7D32.toInt()
                "WARN" -> 0xFFEF6C00.toInt()
                "FAIL" -> 0xFFC62828.toInt()
                else   -> 0xFF666666.toInt()
            }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(r.name)
            if (r.detail.isNotEmpty()) {
                sb.append("\n        ").append(r.detail)
            }
            sb.append("\n")
        }
        val ok   = rows.count { it.status == "OK" }
        val warn = rows.count { it.status == "WARN" }
        val fail = rows.count { it.status == "FAIL" }
        sb.append("\nSummary: $ok OK, $warn warn, $fail fail")
        return sb
    }
}
