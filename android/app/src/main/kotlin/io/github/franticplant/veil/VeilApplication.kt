package io.github.franticplant.veil

import android.app.Application
import io.github.franticplant.veil.engine.EngineBackend
import io.github.franticplant.veil.engine.LocalBackend
import io.github.franticplant.veil.engine.LogRing
import io.github.franticplant.veil.engine.SessionRegistry
import io.github.franticplant.veil.frontend.BuildInfo
import io.github.franticplant.veil.frontend.EngineHost
import io.github.franticplant.veil.frontend.Profiles
import io.github.franticplant.veil.frontend.UiPrefs
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

    /** The app-side log, shared by the session layer and (in dev builds) the dev API. */
    val log = LogRing()

    /** When this process started, for uptime. */
    val startedAtMs = System.currentTimeMillis()

    override val sessions: SessionRegistry by lazy { SessionRegistry(backend, appScope, log) }

    override val profiles: Profiles by lazy { Profiles(this) }

    override val uiPrefs: UiPrefs by lazy { UiPrefs(this) }

    /** For work that must outlive any screen (dev API calls included). */
    val scope: CoroutineScope
        get() = appScope

    override fun onCreate() {
        super.onCreate()
        log.info(
            "app",
            "process started: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        // Keep the process in the foreground while any session is live.
        appScope.launch { SessionService.follow(this@VeilApplication, sessions) }
    }
}
