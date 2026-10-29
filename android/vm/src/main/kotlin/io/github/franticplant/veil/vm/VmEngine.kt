package io.github.franticplant.veil.vm

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.InputStream
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Starts the fixed Microdroid engine payload and returns the connected vsock stream. The AVF
 * classes are system APIs, so this stays reflection-based until we decide how the final package
 * handles that boundary.
 */
object VmEngine {
    private const val NAME = "veil-engine"
    private const val PORT = 5000L
    private val lock = Any()

    @Volatile private var lastLog = ""

    fun logText(): String = lastLog

    private fun record(text: String) {
        lastLog = (lastLog + text + "\n").takeLast(8_000)
        Log.i("VeilVmProbe", text)
    }

    data class Handle(
        val vm: Any,
        val pfd: ParcelFileDescriptor,
        val manager: Any,
        val managerClass: Class<*>,
    )

    fun start(context: Context, protected: Boolean): Handle =
        synchronized(lock) {
            val managerClass = Class.forName("android.system.virtualmachine.VirtualMachineManager")
            val manager =
                context.getSystemService(managerClass) ?: error("VirtualMachineManager unavailable")
            delete(managerClass, manager)

            val builderClass =
                Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            val builder = builderClass.getConstructor(Context::class.java).newInstance(context)
            builderClass
                .getMethod("setProtectedVm", Boolean::class.javaPrimitiveType)
                .invoke(builder, protected)
            builderClass
                .getMethod("setPayloadBinaryName", String::class.java)
                .invoke(builder, "libveil_vm_engine.so")
            optionalLong(builderClass, builder, "setMemoryBytes", 128L * 1024 * 1024)
            optionalInt(builderClass, builder, "setCpuTopology", 0)
            optional(builderClass, builder, "setShouldUseHugepages", true)
            optionalInt(builderClass, builder, "setDebugLevel", 1)
            optional(builderClass, builder, "setVmOutputCaptured", true)
            val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
            val config =
                builderClass.getMethod("build").invoke(builder)
                    ?: error("VirtualMachineConfig.build returned null")
            val vm =
                managerClass
                    .getMethod("getOrCreate", String::class.java, configClass)
                    .invoke(manager, NAME, config) ?: error("getOrCreate returned null")
            val vmClass = Class.forName("android.system.virtualmachine.VirtualMachine")
            setCallback(vmClass, vm)
            vmClass.getMethod("run").invoke(vm)
            drain(vmClass, vm, "getLogOutput", "log")
            drain(vmClass, vm, "getConsoleOutput", "console")

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var last: Throwable? = null
            while (System.nanoTime() < deadline) {
                try {
                    val pfd =
                        vmClass
                            .getMethod("connectVsock", Long::class.javaPrimitiveType)
                            .invoke(vm, PORT) as ParcelFileDescriptor
                    return@synchronized Handle(vm, pfd, manager, managerClass)
                } catch (e: Throwable) {
                    last = e
                    Thread.sleep(200)
                }
            }
            stop(vmClass, vm)
            delete(managerClass, manager)
            throw IllegalStateException("engine vsock did not appear: ${last?.message}", last)
        }

    private fun drain(vmClass: Class<*>, vm: Any, method: String, label: String) {
        Thread {
            runCatching {
                val stream =
                    vmClass.getMethod(method).invoke(vm) as? InputStream ?: return@runCatching
                stream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) record("[VM $label] $line")
                }
            }
                .onFailure { record("[VM $label] ${it.javaClass.simpleName}: ${it.message}") }
        }
            .apply { isDaemon = true }
            .start()
    }

    private fun setCallback(vmClass: Class<*>, vm: Any) {
        runCatching {
            val callbackClass =
                Class.forName("android.system.virtualmachine.VirtualMachineCallback")
            val executor = Executor { it.run() }
            val callback =
                Proxy.newProxyInstance(
                    callbackClass.classLoader,
                    arrayOf(callbackClass),
                ) { _, method, args ->
                    when (method.name) {
                        "onPayloadStarted" -> record("payload started")
                        "onPayloadReady" -> record("payload ready")
                        "onError" -> record("payload error: ${args?.drop(1)?.joinToString(" ")}")
                        "onPayloadFinished" -> {
                            record("payload finished: ${args?.getOrNull(1)}")
                            record(vmLog(vmClass, vm))
                        }
                        "onStopped" -> {
                            record("VM stopped")
                            record(vmLog(vmClass, vm))
                        }
                    }
                    null
                }
            vmClass
                .getMethod("setCallback", Executor::class.java, callbackClass)
                .invoke(vm, executor, callback)
        }
            .onFailure { record("setCallback failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun vmLog(vmClass: Class<*>, vm: Any): String = runCatching {
        fun read(name: String): String =
            when (val value = vmClass.getMethod(name).invoke(vm)) {
                is InputStream -> value.use { it.readBytes().toString(Charsets.UTF_8) }
                null -> ""
                else -> value.toString()
            }
        val log = read("getLogOutput")
        val console = read("getConsoleOutput")
        ("VM log:\n" + log + "\nVM console:\n" + console).takeLast(6_000)
    }
        .getOrDefault("VM log unavailable")

    fun stop(handle: Handle) {
        synchronized(lock) {
            runCatching { handle.pfd.close() }
            stop(Class.forName("android.system.virtualmachine.VirtualMachine"), handle.vm)
            delete(handle.managerClass, handle.manager)
        }
    }

    private fun stop(vmClass: Class<*>, vm: Any) {
        runCatching { vmClass.getMethod("stop").invoke(vm) }
        runCatching { vmClass.getMethod("close").invoke(vm) }
    }

    private fun delete(managerClass: Class<*>, manager: Any) {
        runCatching { managerClass.getMethod("delete", String::class.java).invoke(manager, NAME) }
    }

    private fun optionalLong(
        builderClass: Class<*>,
        builder: Any,
        name: String,
        value: Long,
    ) {
        runCatching {
            builderClass.getMethod(name, Long::class.javaPrimitiveType).invoke(builder, value)
        }
    }

    private fun optionalInt(builderClass: Class<*>, builder: Any, name: String, value: Int) {
        runCatching {
            builderClass.getMethod(name, Int::class.javaPrimitiveType).invoke(builder, value)
        }
    }

    private fun optional(builderClass: Class<*>, builder: Any, name: String, value: Boolean) {
        runCatching {
            builderClass.getMethod(name, Boolean::class.javaPrimitiveType).invoke(builder, value)
        }
    }
}
