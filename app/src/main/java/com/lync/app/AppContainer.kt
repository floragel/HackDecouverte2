package com.lync.app

import android.content.Context
import com.lync.app.data.SessionStore

/** Manual dependency injection: the debug app only keeps the local settings. */
class AppContainer(context: Context) {
    val session = SessionStore(context)
}
