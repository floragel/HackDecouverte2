package com.lync.app.data

import android.content.Context

/** Tiny local persistence: theme and accent colours. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("campus_session", Context.MODE_PRIVATE)

    var accentHex: String
        get() = prefs.getString("accent", "#912338")!!
        set(v) = prefs.edit().putString("accent", v).apply()

    var accent2Hex: String
        get() = prefs.getString("accent2", "#D4A12A")!!
        set(v) = prefs.edit().putString("accent2", v).apply()

    /** "system" | "light" | "dark" */
    var themeMode: String
        get() = prefs.getString("theme", "system")!!
        set(v) = prefs.edit().putString("theme", v).apply()
}
