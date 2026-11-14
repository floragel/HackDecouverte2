package com.lync.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lync.app.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer") }

/** `val vm = campusViewModel { FeedViewModel(it.repository) }` */
@Composable
inline fun <reified VM : ViewModel> campusViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
