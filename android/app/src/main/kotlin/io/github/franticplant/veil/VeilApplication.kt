package io.github.franticplant.veil

import android.app.Application
import io.github.franticplant.veil.engine.EngineBackend
import io.github.franticplant.veil.engine.LocalBackend
import io.github.franticplant.veil.engine.SessionRegistry
import io.github.franticplant.veil.frontend.BuildInfo
import io.github.franticplant.veil.frontend.EngineHost
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires the frontend to a backend. This is the one place that decides where the engine runs; today
 * it is [LocalBackend], in this process.
 */
class VeilApplication : Application(), EngineHost {
    /** Lives as long as the process: sessions must not depend on any screen. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val buildInfo =
        BuildInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            devTools = BuildConfig.DEV_TOOLS,
        )

    private val backend: EngineBackend by lazy {
        LocalBackend(BuildConfig.VERSION_NAME, File(filesDir, "engine"))
    }

    override val backendKind: String
        get() = backend.kind

    override val sessions: SessionRegistry by lazy { SessionRegistry(backend, appScope) }

    override fun onCreate() {
        super.onCreate()
        // Keep the process in the foreground while any session is live.
        appScope.launch { SessionService.follow(this@VeilApplication, sessions) }
    }
}
