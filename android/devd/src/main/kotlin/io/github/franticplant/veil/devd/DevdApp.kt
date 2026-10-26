package io.github.franticplant.veil.devd

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Owns devd's parts. Each is handed what it needs; none reaches the others through a global. */
class DevdApp : Application() {
    val log = DevdLog()
    val access = AccessLog()
    val settings by lazy { Settings(this) }
    val pairings by lazy { Pairings(this, log) }
    val hooks by lazy { Hooks(this, log) }
    val updater by lazy { Updater(this, log) }
    val notifications by lazy { Notifications(this) }
    val methods by lazy { DevdMethods(this) }
    val server by lazy { Server(this) }

    internal val _offAtMs = MutableStateFlow<Long?>(null)

    /** When the listener switches itself off; null while it is off. */
    val offAtMs: StateFlow<Long?> = _offAtMs

    override fun onCreate() {
        super.onCreate()
        log.info("devd", "started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
