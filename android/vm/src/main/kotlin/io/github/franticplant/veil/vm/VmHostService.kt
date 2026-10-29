package io.github.franticplant.veil.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.franticplant.veil.vm.api.IVmHost

/**
 * The only control surface of the VM host package. devd binds it after checking the signature-level
 * permission and can only ask for a report, the fixed probe, or the fixed engine; it cannot name a
 * payload, path or VM configuration.
 */
class VmHostService : Service() {
    private var engine: EngineProxy? = null

    private val binder =
        object : IVmHost.Stub() {
            override fun dump(): String = VmProbe.dump(this@VmHostService).also { log(it) }

            override fun probe(protectedVm: Boolean): String =
                VmProbe.run(this@VmHostService, protectedVm).also { log(it) }

            override fun startEngine(
                protectedVm: Boolean,
                memoryMiB: Int,
                diagnostics: Boolean,
            ): Array<ParcelFileDescriptor> =
                synchronized(this@VmHostService) {
                    engine?.let {
                        it.stop()
                        engine = null
                    }
                    val proxy = EngineProxy(this@VmHostService)
                    val pfds =
                        proxy.start(
                            protectedVm,
                            memoryMiB.coerceIn(32, 1024),
                            diagnostics,
                        )
                    engine = proxy
                    pfds
                }

            override fun dial(
                host: String,
                port: Int,
                tcp: ParcelFileDescriptor,
                connectionId: Long,
            ) {
                synchronized(this@VmHostService) {
                    val proxy = engine ?: throw IllegalStateException("engine VM is not running")
                    proxy.dial(host, port, tcp, connectionId)
                }
            }

            override fun stopEngine() {
                synchronized(this@VmHostService) {
                    engine?.let {
                        it.stop()
                        engine = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }

            override fun engineLog(): String = VmEngine.logText()
        }

    override fun onBind(intent: Intent?): IBinder {
        // Binding can happen before onStartCommand, and starting Microdroid can
        // take several seconds. Become foreground before the first Binder call
        // so LMK does not kill us halfway through boot.
        ensureForeground()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Veil VM", NotificationManager.IMPORTANCE_LOW)
            )
            val notification =
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Veil VM running")
                    .setOngoing(true)
                    .build()
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
            .onFailure { Log.w(TAG, "could not enter the foreground: ${it.message}") }
    }

    override fun onDestroy() {
        synchronized(this) {
            engine?.let {
                it.stop()
                engine = null
            }
        }
        super.onDestroy()
    }

    companion object {
        const val TAG = "VeilVmProbe"
        private const val CHANNEL = "vm"
        private const val NOTIFICATION_ID = 2

        fun log(text: String) {
            text.lineSequence().forEach { Log.i(TAG, it) }
        }
    }
}
