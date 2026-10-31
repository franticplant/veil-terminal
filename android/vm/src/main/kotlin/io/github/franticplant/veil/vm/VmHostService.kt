package io.github.franticplant.veil.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import io.github.franticplant.veil.vm.api.IVmHost
import org.json.JSONObject

/**
 * The only control surface of the VM host package. devd binds it after checking the signature-level
 * permission and can only ask for a report, the fixed probe, or the fixed engine; it cannot name a
 * payload, path or VM configuration.
 */
class VmHostService : Service() {
    private var engine: EngineProxy? = null
    private var clientToken: IBinder? = null
    private var clientDeath: IBinder.DeathRecipient? = null
    @Volatile private var foreground = false

    private val binder =
        object : IVmHost.Stub() {
            override fun dump(): String = VmProbe.dump(this@VmHostService).also { log(it) }

            override fun probe(protectedVm: Boolean): String =
                VmProbe.run(this@VmHostService, protectedVm).also { log(it) }

            override fun probeShell(protectedVm: Boolean, commands: String): String =
                if (!BuildConfig.DEV_PROBE) {
                    "diagnostic shell is not in this build"
                } else {
                    VmProbe.shell(this@VmHostService, protectedVm, commands).also { log(it) }
                }

            override fun probeStop(): String {
                val result = VmProbe.stop().also { log(it) }
                if (engine == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return result
            }

            override fun startEngine(
                protectedVm: Boolean,
                memoryMiB: Int,
                diagnostics: Boolean,
                client: IBinder,
            ): Array<ParcelFileDescriptor> =
                startEngineInternal(protectedVm, memoryMiB, diagnostics, "monolith", client)

            override fun startEngineWithLayout(
                protectedVm: Boolean,
                memoryMiB: Int,
                diagnostics: Boolean,
                layout: String,
                client: IBinder,
            ): Array<ParcelFileDescriptor> =
                startEngineInternal(protectedVm, memoryMiB, diagnostics, layout, client)

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

            override fun closeDial(connectionId: Long) {
                synchronized(this@VmHostService) {
                    engine?.closeDial(connectionId)
                }
            }

            override fun stopEngine() {
                synchronized(this@VmHostService) {
                    stopEngineInternal()
                }
            }

            override fun engineLog(): String = VmEngine.logText()

            override fun stats(): String {
                val base = runCatching {
                    JSONObject(engine?.stats()?.toString() ?: "{}")
                }
                    .getOrDefault(JSONObject())
                return base.put("foreground", foreground).toString()
            }
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

    private fun startEngineInternal(
        protectedVm: Boolean,
        memoryMiB: Int,
        diagnostics: Boolean,
        layout: String,
        client: IBinder,
    ): Array<ParcelFileDescriptor> =
        synchronized(this) {
            engine?.let {
                if (it.hasActiveConnections()) {
                    throw IllegalStateException("the VM engine still has active sessions")
                }
                it.stop()
                engine = null
            }
            linkClient(client)
            val proxy = EngineProxy(this)
            val pfds = proxy.start(protectedVm, memoryMiB.coerceIn(32, 1024), diagnostics, layout)
            engine = proxy
            pfds
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
            foreground = true
        }
            .onFailure {
                foreground = false
                Log.w(TAG, "could not enter the foreground: ${it.message}")
            }
    }

    private fun linkClient(client: IBinder) {
        unlinkClient()
        val death = IBinder.DeathRecipient {
            synchronized(this) {
                Log.i(TAG, "client died; stopping the VM")
                stopEngineInternal()
            }
        }
        try {
            client.linkToDeath(death, 0)
            clientToken = client
            clientDeath = death
        } catch (_: RemoteException) {
            // The caller died before the engine could start.
            stopEngineInternal()
        }
    }

    private fun unlinkClient() {
        clientDeath?.let { death ->
            runCatching { clientToken?.unlinkToDeath(death, 0) }
        }
        clientDeath = null
        clientToken = null
    }

    private fun stopEngineInternal() {
        engine?.let {
            it.stop()
            engine = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        unlinkClient()
    }

    override fun onDestroy() {
        synchronized(this) {
            engine?.let {
                it.stop()
                engine = null
            }
            VmProbe.stop()
            unlinkClient()
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
