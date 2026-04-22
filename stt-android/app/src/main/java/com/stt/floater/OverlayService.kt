package com.stt.floater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubbleContainer: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var targetLabel: TextView
    private lateinit var sessionMenu: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: Prefs
    private var menuExpanded = false

    private var recorder: WavRecorder? = null
    @Volatile private var recording = false

    @Volatile private var detectedSession: String = ""
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!prefs.autoDetectTmux) {
                pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                return
            }
            val url = prefs.serverUrl
            if (url.isNotBlank()) {
                SttClient.fetchActiveSession(url, prefs.token) { session ->
                    if (session != detectedSession) {
                        detectedSession = session
                        bubble.post { refreshTargetLabel() }
                    }
                }
            }
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startAsForeground()
        setupBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "STT bubble", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("STT bubble active")
            .setContentText("Tap bubble to start/stop recording")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun setupBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = TextView(this).apply {
            text = "🎤"
            textSize = 22f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_idle)
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val density = resources.displayMetrics.density
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(Color.argb(200, 20, 20, 20))
        }
        targetLabel = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            background = pillBg
            val padX = (10 * density).toInt()
            val padY = (3 * density).toInt()
            setPadding(padX, padY, padX, padY)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { if (menuExpanded) collapseMenu() else expandMenu() }
        }

        sessionMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        bubbleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(targetLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (4 * density).toInt()
            })
            addView(sessionMenu, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (4 * density).toInt()
            })
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 240
        }

        attachTouchListener()
        wm.addView(bubbleContainer, params)
        refreshTargetLabel()
        pollHandler.post(pollRunnable)
    }

    private fun refreshTargetLabel() {
        val explicit = prefs.explicitTmuxSession.trim()
        val text = when {
            explicit.isNotEmpty() -> explicit
            prefs.autoDetectTmux && detectedSession.isNotEmpty() -> detectedSession
            prefs.autoDetectTmux -> "(searching…)"
            prefs.clipboardMode -> "📋"
            else -> ""
        }
        if (text.isEmpty()) {
            targetLabel.visibility = View.GONE
        } else {
            targetLabel.text = text
            targetLabel.visibility = View.VISIBLE
        }
    }

    private fun expandMenu() {
        sessionMenu.removeAllViews()
        sessionMenu.addView(makeMenuItem("loading…", null))
        sessionMenu.visibility = View.VISIBLE
        menuExpanded = true

        val url = prefs.serverUrl
        if (url.isBlank()) {
            sessionMenu.removeAllViews()
            sessionMenu.addView(makeMenuItem("no server URL", null))
            return
        }
        SttClient.fetchSessions(url, prefs.token) { sessions ->
            bubble.post {
                if (!menuExpanded) return@post
                sessionMenu.removeAllViews()
                val explicit = prefs.explicitTmuxSession.trim()
                if (sessions.isEmpty()) {
                    sessionMenu.addView(makeMenuItem("(no sessions)", null))
                }
                for (name in sessions) {
                    val isCurrent = explicit == name ||
                        (explicit.isEmpty() && detectedSession == name)
                    sessionMenu.addView(makeMenuItem(
                        if (isCurrent) "• $name" else name
                    ) { selectSession(name) })
                }
                // Always show Auto option last (clears explicit pick).
                val autoIsCurrent = explicit.isEmpty()
                sessionMenu.addView(makeMenuItem(
                    if (autoIsCurrent) "• Auto" else "Auto"
                ) { selectSession("") })
            }
        }
    }

    private fun collapseMenu() {
        sessionMenu.visibility = View.GONE
        sessionMenu.removeAllViews()
        menuExpanded = false
    }

    private fun selectSession(name: String) {
        prefs.explicitTmuxSession = name
        // If user picked Auto, force the pill into "searching" until the next
        // poll arrives with a real session name.
        if (name.isEmpty()) detectedSession = ""
        else detectedSession = name
        refreshTargetLabel()
        collapseMenu()
    }

    private fun makeMenuItem(text: String, onClick: (() -> Unit)?): TextView {
        val density = resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(Color.argb(220, 30, 30, 30))
        }
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.WHITE)
            background = bg
            val padX = (12 * density).toInt()
            val padY = (5 * density).toInt()
            setPadding(padX, padY, padX, padY)
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                topMargin = (2 * density).toInt()
            }
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
    }

    private fun attachTouchListener() {
        var startX = 0
        var startY = 0
        var touchRawX = 0f
        var touchRawY = 0f
        var moved = false
        val slop = 12f * resources.displayMetrics.density

        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchRawX = e.rawX
                    touchRawY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchRawX
                    val dy = e.rawY - touchRawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(bubbleContainer, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (recording) stopAndUpload() else startRecording()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun startRecording() {
        if (recording) return
        val r = WavRecorder()
        try {
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "recorder start failed", e)
            toast("recorder failed: ${e.message}")
            return
        }
        recorder = r
        recording = true
        bubble.setBackgroundResource(R.drawable.bubble_listening)
        refreshTargetLabel()
        toast("Recording — tap again to stop")
    }

    private fun stopAndUpload() {
        if (!recording) return
        recording = false
        val r = recorder ?: return
        recorder = null
        bubble.setBackgroundResource(R.drawable.bubble_idle)

        val wav = try { r.stop() } catch (e: Exception) {
            Log.e(TAG, "stop failed", e); toast("stop failed"); return
        }
        if (wav.size < 2000) {
            toast("too short — hold longer")
            return
        }

        val url = prefs.serverUrl
        if (url.isBlank()) {
            toast("Set server URL in the app first")
            return
        }
        val explicit = prefs.explicitTmuxSession.trim()
        val tmuxTarget = when {
            explicit.isNotEmpty() -> explicit
            prefs.autoDetectTmux -> detectedSession
            else -> ""
        }
        val useTmux = tmuxTarget.isNotEmpty()
        val clipboardMode = prefs.clipboardMode
        // When routing via tmux, PC paste and clipboard copy both skipped.
        val pasteOnPc = !useTmux && !clipboardMode
        // In PC-paste mode, always press Enter (user has no separate toggle for it
        // — the one knob is "use clipboard vs PC"). In clipboard mode, Enter is
        // controlled by the sub-toggle "then paste in & press enter". In tmux
        // mode, Enter is built into the server's tmux send-keys flow.
        val submit = if (clipboardMode) prefs.clipboardAutoEnter else true
        toast("Transcribing… (${wav.size / 1024} KB)")
        SttClient.sendAudio(url, prefs.token, wav, submit, pasteOnPc, tmuxTarget) { ok, msg ->
            bubble.post {
                if (ok && useTmux) {
                    toast("$tmuxTarget: $msg")
                } else if (ok && clipboardMode) {
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val payload = if (prefs.clipboardAutoEnter) "$msg\n" else msg
                        cm.setPrimaryClip(ClipData.newPlainText("stt", payload))

                        // If the sub-toggle is on, also broadcast to the
                        // AccessibilityService (if user enabled it) so non-Termux
                        // apps get an auto-paste + Enter. Termux ignores this and
                        // falls back to the clipboard the user manually pastes.
                        if (prefs.clipboardAutoEnter) {
                            try {
                                val intent = Intent(SttAccessibilityService.ACTION_PASTE)
                                    .setPackage(packageName)
                                    .putExtra(SttAccessibilityService.EXTRA_TEXT, msg)
                                    .putExtra(SttAccessibilityService.EXTRA_SUBMIT, true)
                                sendBroadcast(intent)
                            } catch (e: Exception) {
                                Log.w(TAG, "broadcast failed", e)
                            }
                        }

                        val suffix = if (prefs.clipboardAutoEnter) " ⏎" else ""
                        toast("📋 $msg$suffix")
                    } catch (e: Exception) {
                        Log.e(TAG, "clipboard set failed", e)
                        toast("✗ clipboard: ${e.message}")
                    }
                } else {
                    toast(if (ok) "✓ $msg" else "✗ $msg")
                }
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        recording = false
        pollHandler.removeCallbacks(pollRunnable)
        try { recorder?.stop() } catch (_: Exception) {}
        try { wm.removeView(bubbleContainer) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.stt.floater.STOP"
        private const val CHANNEL_ID = "stt_bubble"
        private const val NOTIF_ID = 1
        private const val TAG = "OverlayService"
        private const val POLL_INTERVAL_MS = 4_000L
    }
}
