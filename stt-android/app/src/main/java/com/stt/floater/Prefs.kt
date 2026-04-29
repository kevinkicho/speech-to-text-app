package com.stt.floater

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("stt", Context.MODE_PRIVATE)

    init {
        runMigrations()
    }

    /** Drops keys that were used by older builds. Bumps prefs_version each
     *  time we add a new cleanup so we only do the work once per install. */
    private fun runMigrations() {
        val current = sp.getInt("prefs_version", 0)
        if (current >= PREFS_VERSION) return
        val edit = sp.edit()
        if (current < 1) {
            // v1: drop the old auto-detect toggle and the unused tmux_target string.
            edit.remove("auto_detect_tmux")
            edit.remove("tmux_target")
        }
        edit.putInt("prefs_version", PREFS_VERSION).apply()
    }

    var serverUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) { sp.edit().putString("url", v).apply() }

    var token: String
        get() = sp.getString("token", "change-me") ?: "change-me"
        set(v) { sp.edit().putString("token", v).apply() }

    var submit: Boolean
        get() = sp.getBoolean("submit", true)
        set(v) { sp.edit().putBoolean("submit", v).apply() }

    /** When true, transcribed text is copied to this device's clipboard instead
     *  of being pasted into the PC's focused window. */
    var clipboardMode: Boolean
        get() = sp.getBoolean("clipboard_mode", false)
        set(v) { sp.edit().putBoolean("clipboard_mode", v).apply() }

    /** In clipboard mode: append a newline to the copied text so pasting it
     *  into a terminal submits immediately (one tap paste = submit). */
    var clipboardAutoEnter: Boolean
        get() = sp.getBoolean("clipboard_auto_enter", false)
        set(v) { sp.edit().putBoolean("clipboard_auto_enter", v).apply() }

    /** User's explicit tmux-session pick from the tap-to-pick menu. Empty
     *  means "auto" (most-recently-attached). When non-empty, every utterance
     *  is routed there regardless of which session is on the phone screen. */
    var explicitTmuxSession: String
        get() = sp.getString("explicit_tmux_session", "") ?: ""
        set(v) { sp.edit().putString("explicit_tmux_session", v).apply() }

    /** OPT-IN safety toggle. When true, the server is allowed to fall back to
     *  pasting transcribed text into whatever Windows window has focus if tmux
     *  routing fails. Off by default — without this, a tmux miss returns the
     *  text but does not type it anywhere on the PC, which is the safer default
     *  (avoids dictation landing in Slack / Chrome / etc.). */
    var pcPasteFallback: Boolean
        get() = sp.getBoolean("pc_paste_fallback", false)
        set(v) { sp.edit().putBoolean("pc_paste_fallback", v).apply() }

    /** OPT-IN safety toggle. When true, the phone shows a preview of the
     *  transcribed text and waits for an explicit tap before delivering. Slower
     *  by one tap per utterance, but eliminates the "Whisper misheard X as Y"
     *  category of incidents in destinations like a shell prompt. */
    var confirmBeforeSend: Boolean
        get() = sp.getBoolean("confirm_before_send", false)
        set(v) { sp.edit().putBoolean("confirm_before_send", v).apply() }

    companion object {
        private const val PREFS_VERSION = 1
    }
}
