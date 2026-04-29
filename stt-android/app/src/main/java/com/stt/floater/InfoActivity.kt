package com.stt.floater

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import com.stt.floater.databinding.ActivityInfoBinding

/**
 * Plain "About" screen — what this app is, what it pairs with, what it talks
 * to, and the basic privacy posture. Reached from the `?` button on the main
 * screen.
 */
class InfoActivity : AppCompatActivity() {
    private lateinit var b: ActivityInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.infoText.movementMethod = LinkMovementMethod.getInstance()
        b.infoText.text = HtmlCompat.fromHtml(INFO_HTML, HtmlCompat.FROM_HTML_MODE_LEGACY)

        b.openClaudeSessionsBtn.setOnClickListener {
            openUrl("https://github.com/kevinkicho/claude-sessions-app")
        }
        b.openTailscaleBtn.setOnClickListener {
            openUrl("https://tailscale.com/")
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    companion object {
        // Inline HTML lets us format without extra string-resource churn. Kept
        // close to the activity so it can be edited alongside the layout.
        private val INFO_HTML = """
            <h2>What this app does</h2>
            <p>STT Floater is a floating microphone bubble that records your voice on Android,
            sends the audio to a Whisper transcription server running on your PC, and routes
            the resulting text into a tmux session on your PC — typed and submitted, no
            manual paste.</p>

            <h2>Designed to pair with claude-sessions-app</h2>
            <p>This app is designed to be used together with
            <b>claude-sessions-app</b> (github.com/kevinkicho/claude-sessions-app),
            which manages the named tmux sessions (<i>ses1</i>, <i>ses2</i>, <i>ses3</i>, ...)
            that this app routes into. Without claude-sessions-app — or some other tmux
            setup — there's nowhere for the transcribed text to land except a generic
            PC paste.</p>

            <h2>Connectivity</h2>
            <p>Phone and PC must be on the <b>same Tailscale network</b>. The phone reaches
            the PC at its Tailscale IP (<i>100.x.x.x</i>) over your private mesh — works
            from anywhere with Internet, no port forwarding, no public exposure. The server
            refuses requests from non-Tailnet origins as a defense-in-depth measure.</p>

            <h2>How it routes</h2>
            <ul>
            <li><b>Pill shows • sesN</b> (you picked it from the menu): always sends there
              via <code>tmux send-keys</code> + Enter.</li>
            <li><b>Pill shows sesN</b> (auto-detected): sends to the most-recently-attached
              tmux session. Updates when you start recording or open the picker.</li>
            <li><b>Pill shows 📋</b>: clipboard mode — copies to phone clipboard instead.
              Optional auto-paste-Enter via Android Accessibility Service for non-Termux apps.</li>
            <li><b>Pill turns red ⚠</b>: last send to your picked session didn't reach
              there (session deleted, tmux down). Pick a new target or restart tmux.</li>
            </ul>

            <h2>Safety toggles</h2>
            <ul>
            <li><b>PC paste fallback</b> (off by default): if tmux fails, type the text
              into whatever Windows window has focus. Off avoids dictation landing in
              Slack / browser by accident.</li>
            <li><b>Confirm before sending</b> (off by default): show the transcribed
              text and a ✓ button; nothing routes until you tap it. Useful when your
              session is a shell.</li>
            </ul>

            <h2>Privacy</h2>
            <p>Audio leaves your phone only over Tailscale (WireGuard-encrypted) to the
            specific PC you set up. Whisper runs locally on the PC — nothing goes to
            OpenAI or any cloud. Transcribed text is never logged in full; the server
            only logs metadata (origin IP, status code, errors).</p>

            <h2>Requirements recap</h2>
            <ul>
            <li>Android 9+ with mic + draw-over-other-apps permissions.</li>
            <li>Tailscale installed and signed in on both phone and PC.</li>
            <li>STT server (this project's <code>server.py</code>) running on the PC,
              ideally as the autostart watchdog.</li>
            <li>WSL with tmux for the routing target — claude-sessions-app provides a
              ready-made setup.</li>
            </ul>
        """.trimIndent()
    }
}
