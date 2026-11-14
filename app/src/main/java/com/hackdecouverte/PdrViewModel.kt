package com.hackdecouverte

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hackdecouverte.backend.IndoorNavigationBackend
import com.hackdecouverte.backend.NavigationSnapshot
import kotlinx.coroutines.flow.StateFlow

class PdrViewModel(application: Application) : AndroidViewModel(application) {
    val backend = IndoorNavigationBackend(application, viewModelScope)
    val snapshot: StateFlow<NavigationSnapshot> = backend.state
}
