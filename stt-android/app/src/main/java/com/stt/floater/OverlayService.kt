package com.stt.floater

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
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
    private lateinit var confirmRow: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: Prefs
    private lateinit var pillBgIdle: GradientDrawable
    private lateinit var pillBgError: GradientDrawable
    private lateinit var pillBgPreview: GradientDrawable
    private lateinit var wavQueue: WavQueue
    private var menuExpanded = false
    private var spinAnimator: ObjectAnimator? = null

    /** Pending confirm-before-send state. When non-empty, the user has spoken
     *  and the transcribed text is awaiting their tap to actually send. */
    private var pendingConfirmText: String = ""
    private var pendingConfirmTmuxTarget: String = ""
    private var pendingConfirmPaste: Boolean = false
    private var pendingConfirmSubmit: Boolean = true
    private var pendingConfirmIntendedTarget: String = ""
    private val confirmHandler = Handler(Looper.getMainLooper())
    private val confirmTimeoutRunnable = Runnable { dismissConfirm("⌛ confirm timed out") }
    private val confirmTimeoutMs = 15_000L

    private var recorder: WavRecorder? = null
    @Volatile private var recording = false

    @Volatile private var detectedSession: String = ""
    /** Set when the last upload that targeted an explicit session did NOT
     *  return that session in the response (server fell through). Cleared on
     *  the next successful explicit send. Drives the pill's red error tint. */
    @Volatile private var lastExplicitSendFailed = false

    /** Fetch the auto-routed tmux session name from the server, on demand.
     *  Called on bubble start, when the user taps the mic, and when picking
     *  "Auto" from the menu. No idle polling — keeps the radio asleep. */
    private fun refreshDetectedSession() {
        val url = prefs.serverUrl
        if (url.isBlank() || prefs.clipboardMode) return
        SttClient.fetchActiveSession(url, prefs.token) { session ->
            if (session != detectedSession) {
                detectedSession = session
                bubble.post { refreshTargetLabel() }
            }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wavQueue = WavQueue(this)
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
        pillBgIdle = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(Color.argb(200, 20, 20, 20))
        }
        pillBgError = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            // Dark red — visible without screaming. Indicates the last send to
            // the picked target did not actually land there.
            setColor(Color.argb(220, 140, 30, 30))
            setStroke((1.5f * density).toInt(), Color.argb(255, 220, 80, 80))
        }
        pillBgPreview = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            // Soft blue/teal — distinct from idle (dark grey) and error (red).
            // Indicates the bubble is showing a preview awaiting confirm.
            setColor(Color.argb(230, 30, 80, 130))
            setStroke((1.5f * density).toInt(), Color.argb(255, 100, 180, 240))
        }
        targetLabel = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            background = pillBgIdle
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

        confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            val sendBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(Color.argb(230, 34, 120, 60))
            }
            val cancelBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(Color.argb(230, 120, 34, 34))
            }
            addView(TextView(this@OverlayService).apply {
                text = "✓ Send"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = sendBg
                val padX = (14 * density).toInt()
                val padY = (6 * density).toInt()
                setPadding(padX, padY, padX, padY)
                isClickable = true
                setOnClickListener { confirmSend() }
            })
            addView(TextView(this@OverlayService).apply {
                text = "✗"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = cancelBg
                val padX = (14 * density).toInt()
                val padY = (6 * density).toInt()
                setPadding(padX, padY, padX, padY)
                isClickable = true
                setOnClickListener { dismissConfirm("✗ cancelled") }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = (6 * density).toInt()
            })
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
            addView(confirmRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (6 * density).toInt()
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
        refreshDetectedSession()
    }

    private fun refreshTargetLabel() {
        val explicit = prefs.explicitTmuxSession.trim()
        val text = when {
            explicit.isNotEmpty() -> explicit
            prefs.clipboardMode -> "📋"
            detectedSession.isNotEmpty() -> detectedSession
            else -> "(searching…)"
        }
        targetLabel.text = if (lastExplicitSendFailed && explicit.isNotEmpty()) "⚠ $text" else text
        targetLabel.background =
            if (lastExplicitSendFailed && explicit.isNotEmpty()) pillBgError else pillBgIdle
        targetLabel.visibility = View.VISIBLE
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
                // "Auto" = no explicit pick; falls back to most-recently-attached.
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
        // Picking a new target clears the error state; we'll evaluate the next
        // send on its own merits.
        lastExplicitSendFailed = false
        if (name.isEmpty()) {
            // Picked "Auto" — clear the cached name and re-fetch from server
            // so the pill shows whatever auto resolves to right now.
            detectedSession = ""
            refreshTargetLabel()
            refreshDetectedSession()
        } else {
            detectedSession = name
            refreshTargetLabel()
        }
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
                        when {
                            // Confirm preview is up — bubble tap cancels.
                            pendingConfirmText.isNotEmpty() -> dismissConfirm("✗ cancelled (bubble tap)")
                            recording -> stopAndUpload()
                            else -> startRecording()
                        }
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
        // Best-effort: try to drain any leftover queued audio from a previous
        // failure first. Doesn't block recording — fires and forgets.
        drainQueueOpportunistically()

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
        // Refresh the pill while the user is talking, so by the time they
        // stop they see the freshest auto-target. Best-effort — the actual
        // routing happens server-side via X-Tmux-Session: auto regardless.
        refreshDetectedSession()
        toast("Recording — tap again to stop")
    }

    private fun startSpinner() {
        bubble.setBackgroundResource(R.drawable.bubble_transcribing)
        spinAnimator?.cancel()
        spinAnimator = ObjectAnimator.ofFloat(bubble, "rotation", 0f, 360f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopSpinner() {
        spinAnimator?.cancel()
        spinAnimator = null
        bubble.rotation = 0f
        bubble.setBackgroundResource(R.drawable.bubble_idle)
    }

    private fun stopAndUpload() {
        if (!recording) return
        recording = false
        val r = recorder ?: return
        recorder = null

        val wav = try { r.stop() } catch (e: Exception) {
            Log.e(TAG, "stop failed", e); toast("stop failed"); stopSpinner(); return
        }
        if (wav.size < 2000) {
            stopSpinner()
            toast("too short — hold longer")
            return
        }

        val url = prefs.serverUrl
        if (url.isBlank()) {
            stopSpinner()
            toast("Set server URL in the app first")
            return
        }
        startSpinner()

        val clipboardMode = prefs.clipboardMode
        val explicit = prefs.explicitTmuxSession.trim()
        // Routing precedence:
        //   1. Explicit pick wins always — user tapped a specific session.
        //   2. Otherwise, clipboard mode opts out of tmux routing entirely.
        //   3. Otherwise, ask the server to auto-route.
        val tmuxTarget = when {
            explicit.isNotEmpty() -> explicit
            clipboardMode -> ""
            else -> "auto"
        }
        // PC-paste fallback is now opt-in — server defaults to NOT pasting.
        // Phone only sends X-Paste: true if the user explicitly enabled the
        // safety toggle in the app settings. Prevents dictation from landing
        // in arbitrary Windows windows when tmux misses.
        val pasteOnPc = prefs.pcPasteFallback && !clipboardMode
        // Submit with Enter unless we're in clipboard mode (where the
        // sub-toggle controls newline appending).
        val submit = if (clipboardMode) prefs.clipboardAutoEnter else true
        toast("Transcribing… (${wav.size / 1024} KB)")

        val intendedTarget = explicit  // capture for pill error / queue retry
        val transcribeOnly = prefs.confirmBeforeSend
        SttClient.sendAudio(url, prefs.token, wav, submit, pasteOnPc, tmuxTarget, transcribeOnly) { ok, msg, routed ->
            bubble.post {
                stopSpinner()
                if (!ok) {
                    // Transport failure (network/timeout). Persist so we don't
                    // lose the utterance to a transient hiccup.
                    wavQueue.enqueue(
                        wav,
                        WavQueue.Meta(
                            tmuxTarget = tmuxTarget,
                            submit = submit,
                            pasteOnPc = pasteOnPc,
                            timestampMs = System.currentTimeMillis(),
                        )
                    )
                    toast("✗ $msg — queued (${wavQueue.size()})")
                    return@post
                }

                if (transcribeOnly) {
                    // Confirm flow: show the text and wait for the user to
                    // tap ✓ or ✗ before delivering anywhere.
                    showConfirmPreview(msg, tmuxTarget, pasteOnPc, submit, intendedTarget)
                    return@post
                }

                // Auto-deliver flow.
                if (intendedTarget.isNotEmpty()) {
                    lastExplicitSendFailed = (routed != intendedTarget)
                    refreshTargetLabel()
                }
                if (routed.isNotEmpty()) {
                    toast("$routed: $msg")
                } else if (clipboardMode) {
                    handleClipboardDelivery(msg)
                } else {
                    toast(if (intendedTarget.isNotEmpty()) "✗ $intendedTarget unreachable: $msg" else "✓ $msg")
                }
            }
        }
    }

    /** Display the transcribed text in the pill and reveal the ✓/✗ buttons.
     *  Auto-cancels after [confirmTimeoutMs] so a forgotten preview doesn't
     *  linger. Cleared by confirmSend() / dismissConfirm(). */
    private fun showConfirmPreview(
        text: String,
        tmuxTarget: String,
        pasteOnPc: Boolean,
        submit: Boolean,
        intendedTarget: String,
    ) {
        pendingConfirmText = text
        pendingConfirmTmuxTarget = tmuxTarget
        pendingConfirmPaste = pasteOnPc
        pendingConfirmSubmit = submit
        pendingConfirmIntendedTarget = intendedTarget

        targetLabel.text = "👁 ${truncate(text, 40)}"
        targetLabel.background = pillBgPreview
        targetLabel.visibility = View.VISIBLE
        confirmRow.visibility = View.VISIBLE

        confirmHandler.removeCallbacks(confirmTimeoutRunnable)
        confirmHandler.postDelayed(confirmTimeoutRunnable, confirmTimeoutMs)
    }

    private fun confirmSend() {
        val text = pendingConfirmText
        if (text.isEmpty()) return
        val tmuxTarget = pendingConfirmTmuxTarget
        val pasteOnPc = pendingConfirmPaste
        val submit = pendingConfirmSubmit
        val intendedTarget = pendingConfirmIntendedTarget
        val clipboardMode = prefs.clipboardMode
        clearConfirmState()

        startSpinner()
        SttClient.deliverText(prefs.serverUrl, prefs.token, text, tmuxTarget, pasteOnPc, submit) { ok, _, routed ->
            bubble.post {
                stopSpinner()
                refreshTargetLabel()
                if (!ok) {
                    toast("✗ deliver failed")
                    return@post
                }
                if (intendedTarget.isNotEmpty()) {
                    lastExplicitSendFailed = (routed != intendedTarget)
                    refreshTargetLabel()
                }
                if (routed.isNotEmpty()) {
                    toast("$routed: $text")
                } else if (clipboardMode) {
                    handleClipboardDelivery(text)
                } else {
                    toast(if (intendedTarget.isNotEmpty()) "✗ $intendedTarget unreachable: $text" else "✓ $text")
                }
            }
        }
    }

    private fun dismissConfirm(reason: String) {
        if (pendingConfirmText.isEmpty()) return
        clearConfirmState()
        toast(reason)
    }

    private fun clearConfirmState() {
        pendingConfirmText = ""
        pendingConfirmTmuxTarget = ""
        pendingConfirmPaste = false
        pendingConfirmSubmit = true
        pendingConfirmIntendedTarget = ""
        confirmRow.visibility = View.GONE
        confirmHandler.removeCallbacks(confirmTimeoutRunnable)
        refreshTargetLabel()
    }

    private fun truncate(s: String, n: Int): String =
        if (s.length <= n) s else s.substring(0, n - 1) + "…"

    private fun handleClipboardDelivery(msg: String) {
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
    }

    /** Pull the oldest queued WAV (if any) and try to upload it. Fires once
     *  per mic interaction; if there are still entries after, the next tap
     *  picks up the next one. Keeps things simple — no aggressive retry. */
    private fun drainQueueOpportunistically() {
        val url = prefs.serverUrl
        if (url.isBlank()) return
        val entry = wavQueue.peekOldest() ?: return
        val wavBytes = try { entry.wavFile.readBytes() } catch (e: Exception) {
            Log.w(TAG, "queue read failed; dropping", e); wavQueue.delete(entry); return
        }
        SttClient.sendAudio(
            url, prefs.token, wavBytes,
            entry.meta.submit, entry.meta.pasteOnPc, entry.meta.tmuxTarget,
        ) { ok, msg, routed ->
            bubble.post {
                if (ok) {
                    wavQueue.delete(entry)
                    val target = if (routed.isNotEmpty()) routed else "queue"
                    toast("$target (replay): $msg")
                } else {
                    Log.i(TAG, "queue replay still failing: $msg")
                    // Leave entry in place; will retry on next interaction.
                }
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        recording = false
        spinAnimator?.cancel()
        confirmHandler.removeCallbacks(confirmTimeoutRunnable)
        try { recorder?.stop() } catch (_: Exception) {}
        try { wm.removeView(bubbleContainer) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.stt.floater.STOP"
        private const val CHANNEL_ID = "stt_bubble"
        private const val NOTIF_ID = 1
        private const val TAG = "OverlayService"
    }
}
