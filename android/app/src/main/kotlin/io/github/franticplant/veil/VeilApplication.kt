package io.github.franticplant.veil

import android.app.Application
import io.github.franticplant.veil.engine.EngineBackend
import io.github.franticplant.veil.engine.HostBackend
import io.github.franticplant.veil.engine.KnownHostsStore
import io.github.franticplant.veil.engine.LocalBackend
import io.github.franticplant.veil.engine.LogRing
import io.github.franticplant.veil.engine.ProcessBackend
import io.github.franticplant.veil.engine.SessionRegistry
import io.github.franticplant.veil.engine.VmBackend
import io.github.franticplant.veil.frontend.BuildInfo
import io.github.franticplant.veil.frontend.EngineHost
import io.github.franticplant.veil.frontend.History
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
    private val engineDir by lazy { File(filesDir, "engine") }

    @Volatile private var switchedBackend: EngineBackend? = null

    private val initialBackend: EngineBackend by lazy {
        when (uiPrefs.values.value.engine) {
            UiPrefs.EnginePlace.NATIVE -> HostBackend(this, BuildConfig.VERSION_NAME, engineDir)
            UiPrefs.EnginePlace.SERVICE -> ProcessBackend(this, BuildConfig.VERSION_NAME, engineDir)
            UiPrefs.EnginePlace.LOCAL -> LocalBackend(BuildConfig.VERSION_NAME, engineDir)
            UiPrefs.EnginePlace.VM ->
                VmBackend(
                    this,
                    engineDir,
                    memoryMiB = uiPrefs.values.value.vmMemoryMiB,
                    diagnostics = BuildConfig.DEV_TOOLS,
                    log = log,
                )
        }
    }

    private val backend: EngineBackend
        get() = switchedBackend ?: initialBackend

    override val backendKind: String
        get() = backend.kind

    /** The app-side log, shared by the session layer and (in dev builds) the dev API. */
    val log = LogRing()

    /** When this process started, for uptime. */
    val startedAtMs = System.currentTimeMillis()

    override val sshKeys: SshKeys by lazy { SshKeys(log) }

    /**
     * Host-key pins live on the Android side, so they keep working in VM mode where the guest
     * cannot read app storage.
     */
    private val knownHosts by lazy {
        KnownHostsStore(File(engineDir, "known_hosts.frontend"), log)
    }

    @Volatile private var switchedSessions: SessionRegistry? = null

    private fun newRegistry(backend: EngineBackend): SessionRegistry =
        SessionRegistry(backend, appScope, log, sshKeys, knownHosts) {
            uiPrefs.values.value.let {
                io.github.franticplant.veil.engine.SessionPolicy(
                    it.requirePostQuantum,
                    it.reconnectAttempts,
                )
            }
        }

    private val initialSessions: SessionRegistry by lazy { newRegistry(initialBackend) }

    override val sessions: SessionRegistry
        get() = switchedSessions ?: initialSessions

    /**
     * A deliberate recovery path from a failed VM start. It only switches when no live session
     * would be lost, persists native as the engine choice, and retires the old engine connection.
     */
    @Synchronized
    override fun useNativeEngine(): Boolean {
        if (backend.kind == "native") return true
        val old = sessions
        if (old.sessions.value.any { !it.status.value.closed }) return false
        val native = HostBackend(this, BuildConfig.VERSION_NAME, engineDir)
        val replacement = newRegistry(native)
        switchedBackend = native
        switchedSessions = replacement
        uiPrefs.update { it.copy(engine = UiPrefs.EnginePlace.NATIVE) }
        appScope.launch { old.restartEngine("switched to native after a VM failure") }
        appScope.launch { SessionService.follow(this@VeilApplication, replacement) }
        return true
    }

    override val profiles: Profiles by lazy { Profiles(this) }

    override val history: History by lazy { History(this) }

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
        // Current product: frontend pins are the only host-key authority. The
        // old per-backend known_hosts file is deliberately discarded rather
        // than migrated; hosts will be trusted once through the normal prompt.
        val legacyKnownHosts = File(engineDir, "known_hosts")
        if (legacyKnownHosts.isFile && legacyKnownHosts.delete()) {
            log.info(
                "hostkeys",
                "discarded legacy native known_hosts; frontend pins are authoritative",
            )
        }
        // Keep the process in the foreground while any session is live.
        appScope.launch { SessionService.follow(this@VeilApplication, sessions) }
    }
}
