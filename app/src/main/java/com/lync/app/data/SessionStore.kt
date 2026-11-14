package com.lync.app.data

import android.content.Context
import com.lync.app.data.model.Profile
import kotlinx.serialization.json.Json

/** Tiny local persistence: which university (for colors) + demo profile. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("campus_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

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

    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(v) = prefs.edit().putBoolean("notifications", v).apply()

    var demoProfile: Profile?
        get() = prefs.getString("demo_profile", null)?.let { runCatching { json.decodeFromString<Profile>(it) }.getOrNull() }
        set(v) = prefs.edit().apply { if (v == null) remove("demo_profile") else putString("demo_profile", json.encodeToString(Profile.serializer(), v)) }.apply()
}
