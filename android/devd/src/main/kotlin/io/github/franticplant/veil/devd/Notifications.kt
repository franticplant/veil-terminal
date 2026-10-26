package io.github.franticplant.veil.devd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.text.DateFormat
import java.util.Date

/** devd's notifications: the ongoing listener one, pairing requests, install confirmations. */
class Notifications(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(
                LISTENER,
                "Dev control listener",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ATTENTION,
                "Pairing and install requests",
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun service(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context,
            code,
            Intent(context, DevdService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    fun listener(addresses: List<String>, offAtMs: Long?): Notification {
        val off =
            offAtMs?.let {
                " · off at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
            } ?: ""
        return Notification.Builder(context, LISTENER)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Veil dev control is on")
            .setContentText(addresses.joinToString().ifEmpty { "starting" } + off)
            .setContentIntent(openApp())
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Stop", service(DevdService.ACTION_STOP, 1))
                    .build()
            )
            .addAction(
                Notification.Action.Builder(null, "+1 hour", service(DevdService.ACTION_EXTEND, 2))
                    .build()
            )
            .build()
    }

    fun pairingRequest(req: PairingRequest) {
        nm.notify(
            PAIRING_ID,
            Notification.Builder(context, ATTENTION)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Pair '${req.name}'? Code ${req.code}")
                .setContentText("Open devd to compare the code and choose what it may do")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearPairingRequest() = nm.cancel(PAIRING_ID)

    /** Android needs a tap for this install (first install of a package, usually). */
    fun confirmInstall(installId: Int, confirm: Intent?) {
        if (confirm == null) return
        val pi =
            PendingIntent.getActivity(
                context,
                installId,
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        nm.notify(
            INSTALL_ID_BASE + (installId and 0xffff),
            Notification.Builder(context, ATTENTION)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Confirm Veil install")
                .setContentText("Android asks for a tap the first time devd installs a package")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val LISTENER = "listener"
        const val ATTENTION = "attention"
        const val LISTENER_ID = 1
        const val PAIRING_ID = 2
        const val INSTALL_ID_BASE = 0x10000
    }
}
