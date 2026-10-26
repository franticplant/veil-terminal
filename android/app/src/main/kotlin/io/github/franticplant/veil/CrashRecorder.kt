package io.github.franticplant.veil

import android.content.Context
import io.github.franticplant.veil.engine.LogRing
import java.io.File

/**
 * Keeps the stack trace of an uncaught exception that ends the process, in app-private storage
 * (never sent anywhere), so the next start can report what happened. Android's own exit history
 * (ApplicationExitInfo) says that and how the process ended; this says where.
 */
object CrashRecorder {
    private const val KEEP = 5

    fun dir(context: Context) = File(context.filesDir, "crashes")

    fun install(context: Context, log: LogRing) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val d = dir(context).apply { mkdirs() }
                File(d, "${System.currentTimeMillis()}.txt")
                    .writeText("thread: ${thread.name}\n" + e.stackTraceToString())
                d.listFiles()?.sortedBy { it.name }?.dropLast(KEEP)?.forEach { it.delete() }
                log.log(
                    LogRing.Level.ERROR,
                    "app",
                    "uncaught ${e.javaClass.name} on ${thread.name}: ${e.message}",
                )
            } catch (_: Throwable) {
                // Recording must never stand in the way of the crash itself.
            }
            previous?.uncaughtException(thread, e)
        }
        dir(context)
            .listFiles()
            ?.maxByOrNull { it.name }
            ?.let {
                log.warn("app", "the previous process crashed; see app.exits (${it.name})")
            }
    }
}
