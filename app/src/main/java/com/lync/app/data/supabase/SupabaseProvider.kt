package com.lync.app.data.supabase

import com.lync.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

object SupabaseProvider {
    val isConfigured: Boolean
        get() = !BuildConfig.FORCE_DEMO && BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /** Accepts "https://xxx.supabase.co" even if "/rest/v1/" was pasted after it. */
    private val url: String get() = BuildConfig.SUPABASE_URL.trim().substringBefore("/rest/").trimEnd('/')

    fun create(): SupabaseClient = createSupabaseClient(
        supabaseUrl = url,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
    ) {
        install(Auth)
        install(Postgrest)
        install(Storage)
        install(Realtime)
    }
}
