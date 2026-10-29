package io.github.franticplant.veil.devd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.franticplant.veil.dev.api.DevIds
import io.github.franticplant.veil.vm.api.IVmHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * devd's client for the VM host package. The service is protected by the signature-level VM_HOST
 * permission and exposes only dump/probe; devd cannot name a payload, path or VM configuration.
 */
class VmHost(private val context: Context) {
    fun dump(): String = call { it.dump() }

    fun probe(protectedVm: Boolean): String = call { it.probe(protectedVm) }

    fun engineLog(): String = call { it.engineLog() }

    fun stop(): String = call {
        it.stopEngine()
        "stopped"
    }

    private fun call(block: (IVmHost) -> String): String {
        val connected = CountDownLatch(1)
        var remote: IVmHost? = null
        var failure: String? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    remote = binder?.let { IVmHost.Stub.asInterface(it) }
                    if (remote == null) failure = "the VM host returned no binder"
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // The call in flight reports the failure.
                }
            }
        val intent = Intent().setClassName(DevIds.VM_PACKAGE, DevIds.VM_HOST_SERVICE)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw IllegalStateException("cannot bind ${DevIds.VM_PACKAGE}; is it installed?")
        }
        try {
            if (!connected.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("timed out binding ${DevIds.VM_PACKAGE}")
            }
            failure?.let { throw IllegalStateException(it) }
            return block(remote ?: throw IllegalStateException("no VM host binder"))
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }
}
