package io.github.franticplant.veil.frontend

import io.github.franticplant.veil.engine.SessionRegistry

/**
 * What the frontend needs from the app it runs in. The Application implements it; the frontend
 * never constructs a backend itself, so swapping the local engine for a brokered or VM-hosted one
 * does not touch this module.
 */
interface EngineHost {
    /** The app's sessions; they outlive any screen. */
    val sessions: SessionRegistry
    /** Saved hosts, shared by every screen (and, in dev builds, the dev API). */
    val profiles: Profiles
    val buildInfo: BuildInfo
    /** Where sessions run, for display ("local", later "vm"). */
    val backendKind: String
}

/** Shown to the user; [devTools] builds carry the development control plane. */
data class BuildInfo(val versionName: String, val versionCode: Long, val devTools: Boolean)
