package com.hackdecouverte

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hackdecouverte.backend.IndoorNavigationBackend
import com.hackdecouverte.map.IndoorSite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds one navigation engine per building. Picking another building stops the current engine
 * and starts a fresh one on that building's floors; the choice is remembered across launches.
 */
class PdrViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var engineScope = newEngineScope()

    private val _backend = MutableStateFlow(
        IndoorNavigationBackend(application, engineScope, IndoorSite.byId(prefs.getString(KEY_SITE, null)))
    )
    val backendFlow: StateFlow<IndoorNavigationBackend> = _backend
    val backend: IndoorNavigationBackend get() = _backend.value

    fun selectSite(site: IndoorSite) {
        if (site == backend.site) return
        backend.shutdown()
        engineScope.cancel()
        engineScope = newEngineScope()
        prefs.edit().putString(KEY_SITE, site.id).apply()
        _backend.value = IndoorNavigationBackend(getApplication(), engineScope, site)
    }

    override fun onCleared() {
        backend.shutdown()
        super.onCleared()
    }

    private fun newEngineScope() =
        CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))

    private companion object {
        const val PREFS_NAME = "indoor_site"
        const val KEY_SITE = "site_id"
    }
}
