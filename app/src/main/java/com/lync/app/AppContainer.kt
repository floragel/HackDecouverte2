package com.lync.app

import android.content.Context
import com.lync.app.data.CampusRepository
import com.lync.app.data.SessionStore
import com.lync.app.data.demo.DemoRepository
import com.lync.app.data.location.LocationClient
import com.lync.app.data.supabase.SupabaseProvider
import com.lync.app.data.supabase.SupabaseRepository

/** Manual dependency injection — simple, and maps 1:1 to an iOS `AppEnvironment`. */
class AppContainer(context: Context) {
    val session = SessionStore(context)
    val location = LocationClient(context)

    val repository: CampusRepository =
        if (SupabaseProvider.isConfigured) runCatching { SupabaseRepository(SupabaseProvider.create()) }.getOrNull() ?: demo()
        else demo()

    /** App-wide scope (lives as long as the process) for background work like location pushes. */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    val locationTracker = com.lync.app.data.location.LocationTracker(context.applicationContext, repository, appScope)

    // Never crash on a bad key: fall back to the offline demo.
    private fun demo() = DemoRepository().also { d -> session.demoProfile?.let(d::restore) }
}
