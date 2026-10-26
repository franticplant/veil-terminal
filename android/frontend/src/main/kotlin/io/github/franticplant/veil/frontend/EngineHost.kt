package io.github.franticplant.veil.frontend

import io.github.franticplant.veil.engine.EngineBackend

/**
 * What the frontend needs from the app it runs in. The Application implements it; the frontend
 * never constructs a backend itself, so swapping the local engine for a brokered or VM-hosted one
 * does not touch this module.
 */
interface EngineHost {
  val engineBackend: EngineBackend
  val buildInfo: BuildInfo
}

/** Shown to the user; [devTools] builds carry the development control plane. */
data class BuildInfo(val versionName: String, val versionCode: Long, val devTools: Boolean)
