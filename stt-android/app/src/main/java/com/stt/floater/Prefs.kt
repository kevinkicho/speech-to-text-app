package com.stt.floater

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("stt", Context.MODE_PRIVATE)

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

    /** When true, the overlay polls the PC periodically for the most-recently-
     *  attached tmux session name and routes transcribed text there via
     *  `tmux send-keys`. Takes priority over PC paste and clipboard modes. */
    var autoDetectTmux: Boolean
        get() = sp.getBoolean("auto_detect_tmux", false)
        set(v) { sp.edit().putBoolean("auto_detect_tmux", v).apply() }

    /** User's explicit tmux-session pick from the tap-to-pick menu. When
     *  non-empty, overrides auto-detect until cleared (picking "Auto"). */
    var explicitTmuxSession: String
        get() = sp.getString("explicit_tmux_session", "") ?: ""
        set(v) { sp.edit().putString("explicit_tmux_session", v).apply() }
}
