package io.github.franticplant.veil

import android.app.Application
import io.github.franticplant.veil.engine.EngineBackend
import io.github.franticplant.veil.engine.LocalBackend
import io.github.franticplant.veil.frontend.BuildInfo
import io.github.franticplant.veil.frontend.EngineHost

/**
 * Wires the frontend to a backend. This is the one place that decides where the engine runs; today
 * it is [LocalBackend], in this process.
 */
class VeilApplication : Application(), EngineHost {
  override val buildInfo =
      BuildInfo(
          versionName = BuildConfig.VERSION_NAME,
          versionCode = BuildConfig.VERSION_CODE.toLong(),
          devTools = BuildConfig.DEV_TOOLS,
      )

  override val engineBackend: EngineBackend by lazy {
    LocalBackend(BuildConfig.VERSION_NAME, java.io.File(filesDir, "engine"))
  }
}
