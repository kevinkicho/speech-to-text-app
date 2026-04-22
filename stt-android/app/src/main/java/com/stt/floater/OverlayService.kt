package com.stt.floater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: Prefs

    private var recorder: WavRecorder? = null
    @Volatile private var recording = false

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
        wm.addView(bubble, params)
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
                        wm.updateViewLayout(bubble, params)
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
        toast("Transcribing… (${wav.size / 1024} KB)")
        SttClient.sendAudio(url, prefs.token, wav, prefs.submit) { ok, msg ->
            bubble.post { toast(if (ok) "✓ $msg" else "✗ $msg") }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        recording = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { wm.removeView(bubble) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.stt.floater.STOP"
        private const val CHANNEL_ID = "stt_bubble"
        private const val NOTIF_ID = 1
        private const val TAG = "OverlayService"
    }
}
