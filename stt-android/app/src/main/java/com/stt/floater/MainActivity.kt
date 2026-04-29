package com.stt.floater

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.stt.floater.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotifIfNeeded()
        else setStatus("Mic permission denied — required for speech.")
    }

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> maybeRequestOverlay() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.urlInput.setText(prefs.serverUrl)
        b.tokenInput.setText(prefs.token)
        b.clipboardSwitch.isChecked = prefs.clipboardMode
        b.clipboardEnterSwitch.isChecked = prefs.clipboardAutoEnter
        b.clipboardEnterSwitch.visibility = if (prefs.clipboardMode) View.VISIBLE else View.GONE
        b.pcPasteFallbackSwitch.isChecked = prefs.pcPasteFallback
        b.confirmBeforeSendSwitch.isChecked = prefs.confirmBeforeSend

        b.clipboardSwitch.setOnCheckedChangeListener { _, checked ->
            b.clipboardEnterSwitch.visibility = if (checked) View.VISIBLE else View.GONE
        }

        b.openAccessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        b.infoButton.setOnClickListener {
            startActivity(Intent(this, InfoActivity::class.java))
        }

        b.diagnosticsButton.setOnClickListener {
            // Save prefs first so the diagnostics see whatever's currently in
            // the form fields, not whatever was saved last time.
            savePrefs()
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        b.startButton.setOnClickListener {
            savePrefs()
            if (prefs.serverUrl.isBlank()) {
                setStatus("Enter the server URL first (e.g. https://your-pc.tail-xxxx.ts.net)")
                return@setOnClickListener
            }
            startFlow()
        }

        b.stopButton.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            setStatus("Bubble stopped.")
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this) && pendingLaunch) {
            pendingLaunch = false
            launchOverlay()
        }
        // Re-check accessibility status on every resume so the banner updates
        // immediately after the user toggles the service on/off in Settings.
        refreshAccessibilityBanner()
    }

    private var pendingLaunch = false

    private fun refreshAccessibilityBanner() {
        // Only show the banner when the user actually wants the auto-paste-Enter
        // feature (clipboard mode + sub-toggle). Without it, the service is
        // optional and we shouldn't nag.
        val needsService = prefs.clipboardMode && prefs.clipboardAutoEnter
        val enabled = isAccessibilityServiceEnabled()
        b.accessibilityBanner.visibility =
            if (needsService && !enabled) View.VISIBLE else View.GONE
    }

    /** Reads the system-level "enabled accessibility services" string and
     *  checks if our SttAccessibilityService is in it. Survives APK reinstall
     *  detection (Android revokes the permission on reinstall). */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${SttAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun savePrefs() {
        prefs.serverUrl = b.urlInput.text.toString().trim()
        prefs.token = b.tokenInput.text.toString().trim().ifEmpty { "change-me" }
        prefs.clipboardMode = b.clipboardSwitch.isChecked
        prefs.clipboardAutoEnter = b.clipboardEnterSwitch.isChecked
        prefs.pcPasteFallback = b.pcPasteFallbackSwitch.isChecked
        prefs.confirmBeforeSend = b.confirmBeforeSendSwitch.isChecked
    }

    private fun startFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestNotifIfNeeded()
    }

    private fun requestNotifIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        maybeRequestOverlay()
    }

    private fun maybeRequestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Grant 'Display over other apps' and return here.")
            pendingLaunch = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        launchOverlay()
    }

    private fun launchOverlay() {
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        setStatus("Bubble running — look for the 🎤 on screen. Hold to talk; release to send.")
    }

    private fun setStatus(s: String) { b.statusText.text = s }
}
