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
}
