package io.github.franticplant.veil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.franticplant.veil.engine.SessionRegistry
import io.github.franticplant.veil.frontend.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * A foreground service that exists while sessions are live, so Android keeps the process (and the
 * engine in it) running when the UI is in the background. It does no work of its own.
 */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val live = intent?.getIntExtra(EXTRA_LIVE, 0) ?: 0
        if (live == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            notification(live),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_NOT_STICKY
    }

    private fun notification(live: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SSH sessions", NotificationManager.IMPORTANCE_LOW)
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (live == 1) "1 SSH session" else "$live SSH sessions")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_LIVE = "live"

        /** Starts, updates and stops the service as the number of live sessions changes. */
        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun follow(context: Context, registry: SessionRegistry) {
            registry.sessions
                .flatMapLatest { list ->
                    if (list.isEmpty()) flowOf(0)
                    else combine(list.map { it.status }) { states -> states.count { !it.closed } }
                }
                .distinctUntilChanged()
                .collect { live ->
                    val intent =
                        Intent(context, SessionService::class.java).putExtra(EXTRA_LIVE, live)
                    try {
                        if (live > 0) context.startForegroundService(intent)
                        else context.startService(intent)
                    } catch (e: IllegalStateException) {
                        // Not allowed from the background right now; the sessions still
                        // run while the process lives.
                    }
                }
        }
    }
}
