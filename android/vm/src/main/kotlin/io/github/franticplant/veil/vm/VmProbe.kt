package io.github.franticplant.veil.vm

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The first AVF probe. It uses reflection because the AVF classes are a system API and are not in
 * the public android.jar. If the runtime hides them, that is itself a probe result: the app reports
 * the exact exception instead of crashing.
 */
object VmProbe {
    private const val PORT = 5000
    private const val VM_NAME = "veil-probe"
    private val lock = Any()

    fun dump(context: Context): String {
        val out = StringBuilder()
        out.appendLine("AVF API dump")
        out.appendLine("feature: ${context.packageManager.hasSystemFeature(AVF_FEATURE)}")
        out.appendLine("manage: ${granted(context, MANAGE)}")
        out.appendLine("custom: ${granted(context, CUSTOM)}")
        out.appendLine()
        for (name in API_CLASSES) {
            out.appendLine("== $name")
            try {
                val c = Class.forName(name)
                out.appendLine("class: ${c.name}")
                c.constructors.forEach { ctor ->
                    out.appendLine("  <init>(${ctor.parameterTypes.joinToString { it.name }})")
                }
                c.methods
                    .sortedBy { it.name }
                    .forEach { m ->
                        out.appendLine("  ${m.name}(${m.parameterTypes.joinToString { it.name }})")
                    }
            } catch (e: Throwable) {
                out.appendLine("  ${e.javaClass.simpleName}: ${e.message}")
            }
            out.appendLine()
        }
        return out.toString()
    }

    /**
     * One probe at a time. The app button and devd both use the same fixed VM name; two concurrent
     * runs would delete and recreate each other's VM.
     */
    fun run(context: Context, protected: Boolean): String =
        synchronized(lock) { runLocked(context, protected) }

    private fun runLocked(context: Context, protected: Boolean): String {
        val out = StringBuilder()
        out.appendLine("Veil VM probe")
        out.appendLine("protected=$protected")
        out.appendLine()
        try {
            out.appendLine(
                "AVF feature: " +
                    if (context.packageManager.hasSystemFeature(AVF_FEATURE)) "present"
                    else "missing"
            )
            out.appendLine("MANAGE_VIRTUAL_MACHINE: ${granted(context, MANAGE)}")
            out.appendLine("USE_CUSTOM_VIRTUAL_MACHINE: ${granted(context, CUSTOM)}")
            if (!granted(context, MANAGE)) {
                out.appendLine()
                out.appendLine("Grant the permission first; no VM was started.")
                return out.toString()
            }

            val managerClass = Class.forName("android.system.virtualmachine.VirtualMachineManager")
            val manager = context.getSystemService(managerClass)
            if (manager == null) {
                out.appendLine("VirtualMachineManager: null (AVF unavailable)")
                return out.toString()
            }
            val capabilities = managerClass.getMethod("getCapabilities").invoke(manager) as Int
            out.appendLine(
                "capabilities: 0x${capabilities.toString(16)} " +
                    "protected=${capabilities and CAP_PROTECTED != 0} " +
                    "nonProtected=${capabilities and CAP_NON_PROTECTED != 0}"
            )
            try {
                val os = managerClass.getMethod("getSupportedOSList").invoke(manager)
                out.appendLine("supported OS: $os")
            } catch (e: Throwable) {
                out.appendLine("supported OS: not exported on this build")
            }
            // The Android 17 public AVF surface (see Dump AVF API) does not
            // expose isFeatureEnabled/isRemoteAttestationSupported/
            // isUpdatableVmSupported; the capability bits are the useful part.

            delete(managerClass, manager)
            val config = buildConfig(context, protected)
            val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
            val vm =
                managerClass
                    .getMethod("getOrCreate", String::class.java, configClass)
                    .invoke(manager, VM_NAME, config) ?: error("getOrCreate returned null")
            val vmClass = Class.forName("android.system.virtualmachine.VirtualMachine")

            val executor = Executors.newSingleThreadExecutor()
            val ready = CountDownLatch(1)
            val failure = AtomicReference<String?>(null)
            val callbackClass =
                Class.forName("android.system.virtualmachine.VirtualMachineCallback")
            val callback =
                Proxy.newProxyInstance(
                    callbackClass.classLoader,
                    arrayOf(callbackClass),
                    InvocationHandler { _, method, args ->
                        when (method.name) {
                            "onPayloadReady" -> ready.countDown()
                            "onError" -> {
                                failure.set("onError ${args?.joinToString(" ")}")
                                ready.countDown()
                            }
                            "onStopped" -> {
                                if (ready.count > 0) {
                                    failure.set("VM stopped before payload ready")
                                    ready.countDown()
                                }
                            }
                            "onPayloadFinished" -> {
                                if (ready.count > 0) {
                                    failure.set("payload finished before ready")
                                    ready.countDown()
                                }
                            }
                        }
                        null
                    },
                )
            try {
                vmClass
                    .getMethod("setCallback", Executor::class.java, callbackClass)
                    .invoke(vm, executor, callback)
            } catch (e: Throwable) {
                out.appendLine("setCallback: ${e.javaClass.simpleName}: ${e.message}")
            }
            out.appendLine("starting VM...")
            vmClass.getMethod("run").invoke(vm)
            val readySeen = ready.await(5, TimeUnit.SECONDS)
            out.appendLine(
                "payload ready callback: " +
                    if (readySeen) "yes" else "not received (continuing to vsock)"
            )
            if (failure.get() != null) out.appendLine("callback: ${failure.get()}")
            out.appendLine(echoWithRetry(vmClass, vm))
            try {
                val log = vmClass.getMethod("getLogOutput").invoke(vm)
                if (log != null) {
                    val text = log.toString()
                    out.appendLine("VM log (tail):")
                    out.appendLine(text.takeLast(4000))
                }
            } catch (_: Throwable) {
                // Log output is optional and only meaningful after a boot.
            }
            try {
                vmClass.getMethod("stop").invoke(vm)
            } catch (e: Throwable) {
                out.appendLine("stop: ${e.javaClass.simpleName}: ${e.message}")
            }
            delete(managerClass, manager)
            executor.shutdownNow()
        } catch (e: Throwable) {
            out.appendLine()
            out.appendLine("probe failed: ${e.javaClass.name}: ${e.message}")
            var cause = e.cause
            while (cause != null) {
                out.appendLine("caused by: ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
            }
        }
        return out.toString()
    }

    private fun buildConfig(context: Context, protected: Boolean): Any {
        val builderClass =
            Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
        val builder = builderClass.getConstructor(Context::class.java).newInstance(context)
        builderClass
            .getMethod("setProtectedVm", Boolean::class.javaPrimitiveType)
            .invoke(builder, protected)
        builderClass
            .getMethod("setPayloadBinaryName", String::class.java)
            .invoke(builder, "libveil_vm_probe.so")
        optionalLong(builderClass, builder, "setMemoryBytes", 256L * 1024 * 1024)
        optionalInt(builderClass, builder, "setCpuTopology", 0)
        // Performance options AOSP's own Microdroid benchmarks use. They are
        // optional: older builds may not expose them.
        optional(builderClass, builder, "setShouldUseHugepages", true)
        optional(builderClass, builder, "setShouldBoostUclamp", true)
        return builderClass.getMethod("build").invoke(builder)
            ?: error("VirtualMachineConfig.build returned null")
    }

    private fun optionalLong(
        builderClass: Class<*>,
        builder: Any,
        name: String,
        value: Long,
    ) {
        try {
            builderClass.getMethod(name, Long::class.javaPrimitiveType).invoke(builder, value)
        } catch (_: Throwable) {
            // Not present on this build; the builder keeps its default.
        }
    }

    private fun optionalInt(builderClass: Class<*>, builder: Any, name: String, value: Int) {
        try {
            builderClass.getMethod(name, Int::class.javaPrimitiveType).invoke(builder, value)
        } catch (_: Throwable) {
            // Not present on this build; the builder keeps its default.
        }
    }

    private fun optional(builderClass: Class<*>, builder: Any, name: String, value: Boolean) {
        try {
            builderClass.getMethod(name, Boolean::class.javaPrimitiveType).invoke(builder, value)
        } catch (_: Throwable) {
            // Not present on this build; the VM still boots with the defaults.
        }
    }

    private fun echoWithRetry(vmClass: Class<*>, vm: Any): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val pfd =
                    vmClass
                        .getMethod("connectVsock", Long::class.javaPrimitiveType)
                        .invoke(vm, PORT.toLong()) as ParcelFileDescriptor
                val writeFd = pfd.dup()
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
                        val greeting = ByteArray(64)
                        val n = input.read(greeting)
                        val text = String(greeting, 0, n.coerceAtLeast(0)).trim()
                        output.write("ping\n".toByteArray())
                        output.flush()
                        val reply = ByteArray(64)
                        val m = input.read(reply)
                        return "vsock: received \"$text\"; echo \"${String(reply, 0, m.coerceAtLeast(0)).trim()}\""
                    }
                }
            } catch (e: Throwable) {
                last = e
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return "vsock: ${last?.javaClass?.simpleName}: ${last?.message}"
    }

    private fun delete(managerClass: Class<*>, manager: Any) {
        try {
            managerClass.getMethod("delete", String::class.java).invoke(manager, VM_NAME)
        } catch (_: Throwable) {
            // No previous instance, or deletion is not possible in this state.
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private const val AVF_FEATURE = "android.software.virtualization_framework"
    private const val MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE"
    private const val CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    private const val CAP_PROTECTED = 1
    private const val CAP_NON_PROTECTED = 2
    private val API_CLASSES =
        listOf(
            "android.system.virtualmachine.VirtualMachineManager",
            "android.system.virtualmachine.VirtualMachineConfig",
            "android.system.virtualmachine.VirtualMachineConfig\$Builder",
            "android.system.virtualmachine.VirtualMachine",
            "android.system.virtualmachine.VirtualMachineCallback",
            "android.system.virtualmachine.VirtualMachineException",
        )
}
