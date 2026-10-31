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
    private class Binding(val connection: ServiceConnection, var remote: IVmHost?)

    /** Holds the probe VM's host service across vm.shell calls. */
    private var shellBinding: Binding? = null

    fun dump(): String = call { it.dump() }

    fun probe(protectedVm: Boolean): String = call { it.probe(protectedVm) }

    fun shell(protectedVm: Boolean, commands: String): String {
        val stale = shellBinding
        if (stale != null && stale.remote == null) {
            unbind(stale)
            shellBinding = null
        }
        val binding = shellBinding ?: bind().also { shellBinding = it }
        return binding.remote?.probeShell(protectedVm, commands)
            ?: throw IllegalStateException("the VM host binder went away")
    }

    fun probeStop(): String {
        val binding = shellBinding?.takeIf { it.remote != null } ?: bind()
        return try {
            binding.remote?.probeStop() ?: throw IllegalStateException("no VM host binder")
        } finally {
            unbind(binding)
            if (shellBinding === binding) shellBinding = null
        }
    }

    fun engineLog(): String = call { it.engineLog() }

    fun stats(): String = call { it.stats() }

    fun stop(): String = call {
        it.stopEngine()
        "stopped"
    }

    private fun call(block: (IVmHost) -> String): String {
        val binding = bind()
        return try {
            block(binding.remote ?: throw IllegalStateException("no VM host binder"))
        } finally {
            unbind(binding)
        }
    }

    private fun bind(): Binding {
        val connected = CountDownLatch(1)
        var failure: String? = null
        lateinit var binding: Binding
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    binding.remote = binder?.let { IVmHost.Stub.asInterface(it) }
                    if (binding.remote == null) failure = "the VM host returned no binder"
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    binding.remote = null
                }
            }
        binding = Binding(connection, null)
        val intent = Intent().setClassName(DevIds.VM_PACKAGE, DevIds.VM_HOST_SERVICE)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw IllegalStateException("cannot bind ${DevIds.VM_PACKAGE}; is it installed?")
        }
        try {
            if (!connected.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("timed out binding ${DevIds.VM_PACKAGE}")
            }
            failure?.let { throw IllegalStateException(it) }
            return binding
        } catch (e: Throwable) {
            unbind(binding)
            throw e
        }
    }

    private fun unbind(binding: Binding) {
        runCatching { context.unbindService(binding.connection) }
        binding.remote = null
    }
}
