package io.github.franticplant.veil.devd

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Runs the listener as a foreground service, so it is visible (an ongoing notification) and Android
 * keeps it alive. It is started only from devd's own screen and switches itself off after the
 * configured time.
 */
class DevdService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app
        get() = application as DevdApp

    private val autoOff = Runnable {
        app.log.info("listener", "auto-off")
        shutDown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutDown()
                return START_NOT_STICKY
            }
            ACTION_EXTEND -> schedule(System.currentTimeMillis() + 60 * 60_000L)
            else -> {
                startForeground(
                    Notifications.LISTENER_ID,
                    app.notifications.listener(emptyList(), null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                app.server.start()
                // Resumed after an update: keep the time that was left; else a fresh period.
                val resumeAt = intent?.getLongExtra(EXTRA_OFF_AT, 0L) ?: 0L
                schedule(
                    if (resumeAt > System.currentTimeMillis()) resumeAt
                    else
                        System.currentTimeMillis() +
                            app.settings.values.value.autoOffMinutes * 60_000L
                )
                scope.launch {
                    combine(app.server.addresses, app.offAtMs) { a, off -> a to off }
                        .collect { (a, off) ->
                            if (off != null) {
                                getSystemService(android.app.NotificationManager::class.java)
                                    .notify(
                                        Notifications.LISTENER_ID,
                                        app.notifications.listener(a, off),
                                    )
                            }
                        }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Switches off at [offAtMs]. Remembered, so an update (which ends this process) can resume. */
    private fun schedule(offAtMs: Long) {
        handler.removeCallbacks(autoOff)
        handler.postDelayed(autoOff, (offAtMs - System.currentTimeMillis()).coerceAtLeast(0))
        app._offAtMs.value = offAtMs
        app.settings.rememberListener(offAtMs)
    }

    private fun shutDown() {
        // Switched off on purpose: nothing to resume.
        app.settings.rememberListener(null)
        handler.removeCallbacks(autoOff)
        app.server.stop()
        app.hooks.unbind()
        app._offAtMs.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoOff)
        app.server.stop()
        app._offAtMs.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_EXTEND = "extend"
        const val EXTRA_OFF_AT = "offAt"

        /** Resumes a listener that was on when this process ended (an update), until [offAtMs]. */
        fun resume(context: Context, offAtMs: Long) =
            context.startForegroundService(
                Intent(context, DevdService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_OFF_AT, offAtMs)
            )

        fun start(context: Context) =
            context.startForegroundService(
                Intent(context, DevdService::class.java).setAction(ACTION_START)
            )

        fun stop(context: Context) =
            context.startService(Intent(context, DevdService::class.java).setAction(ACTION_STOP))
    }
}
