package io.github.franticplant.veil

import android.app.Application
import io.github.franticplant.veil.engine.EngineBackend
import io.github.franticplant.veil.engine.HostBackend
import io.github.franticplant.veil.engine.LocalBackend
import io.github.franticplant.veil.engine.LogRing
import io.github.franticplant.veil.engine.ProcessBackend
import io.github.franticplant.veil.engine.SessionRegistry
import io.github.franticplant.veil.frontend.BuildInfo
import io.github.franticplant.veil.frontend.EngineHost
import io.github.franticplant.veil.frontend.Profiles
import io.github.franticplant.veil.frontend.SshKeys
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

    /**
     * Where the engine runs, chosen at start from the settings: a native process of its own
     * (veil-engine-host), the `:engine` Java process, or this process through JNI. All three serve
     * the same protocol from the same engine state directory. Changing it applies at the next
     * start.
     */
    private val backend: EngineBackend by lazy {
        val dir = File(filesDir, "engine")
        when (uiPrefs.values.value.engine) {
            UiPrefs.EnginePlace.NATIVE -> HostBackend(this, BuildConfig.VERSION_NAME, dir)
            UiPrefs.EnginePlace.SERVICE -> ProcessBackend(this, BuildConfig.VERSION_NAME, dir)
            UiPrefs.EnginePlace.LOCAL -> LocalBackend(BuildConfig.VERSION_NAME, dir)
        }
    }

    override val backendKind: String
        get() = backend.kind

    /** The app-side log, shared by the session layer and (in dev builds) the dev API. */
    val log = LogRing()

    /** When this process started, for uptime. */
    val startedAtMs = System.currentTimeMillis()

    override val sshKeys: SshKeys by lazy { SshKeys(log) }

    override val sessions: SessionRegistry by lazy {
        SessionRegistry(backend, appScope, log, sshKeys) {
            uiPrefs.values.value.let {
                io.github.franticplant.veil.engine.SessionPolicy(
                    it.requirePostQuantum,
                    it.reconnectAttempts,
                )
            }
        }
    }

    override val profiles: Profiles by lazy { Profiles(this) }

    override val uiPrefs: UiPrefs by lazy { UiPrefs(this) }

    /** For work that must outlive any screen (dev API calls included). */
    val scope: CoroutineScope
        get() = appScope

    override fun onCreate() {
        super.onCreate()
        // The :engine process runs only EngineProcessService; none of the app is set up there.
        if (getProcessName().endsWith(":engine")) return
        CrashRecorder.install(this, log)
        log.info(
            "app",
            "process started: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        // Keep the process in the foreground while any session is live.
        appScope.launch { SessionService.follow(this@VeilApplication, sessions) }
    }
}
