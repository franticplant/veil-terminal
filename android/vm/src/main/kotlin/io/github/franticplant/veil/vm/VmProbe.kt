package io.github.franticplant.veil.vm

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The AVF diagnostic probe. It uses reflection because the AVF classes are a system API and are not
 * in the public android.jar. If the runtime hides them, that is itself a probe result: the app
 * reports the exact exception instead of crashing.
 *
 * The guest payload is a small command shell. A one-shot [run] boots, asks for `env`, and stops.
 * [shell] keeps the probe VM alive so a workstation can send `env`, `ps`, `syscall`, `seccomp`, and
 * other commands through the dev control plane without rebuilding the payload each time.
 */
object VmProbe {
    private const val PORT = 5000
    private const val VM_NAME = "veil-probe"
    private const val END = "\n__VEIL_PROBE_END__\n"
    private val lock = Any()
    private var session: Session? = null

    private class Session(
        val protectedVm: Boolean,
        val managerClass: Class<*>,
        val manager: Any,
        val vmClass: Class<*>,
        val vm: Any,
        val executor: ExecutorService,
    )

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
                c.declaredMethods
                    .filter {
                        it.name.contains("Payload", ignoreCase = true) ||
                            it.name.contains("Config", ignoreCase = true) ||
                            it.name.contains("Extra", ignoreCase = true)
                    }
                    .sortedBy { it.name }
                    .forEach { m ->
                        out.appendLine(
                            "  declared ${m.modifiers} ${m.name}(" +
                                m.parameterTypes.joinToString { it.name } +
                                ")"
                        )
                    }
                c.declaredFields
                    .filter {
                        it.name.contains("ayload", ignoreCase = true) ||
                            it.name.contains("onfig", ignoreCase = true)
                    }
                    .sortedBy { it.name }
                    .forEach { f -> out.appendLine("  field ${f.modifiers} ${f.name}") }
            } catch (e: Throwable) {
                out.appendLine("  ${e.javaClass.simpleName}: ${e.message}")
            }
            out.appendLine()
        }
        return out.toString()
    }

    /**
     * One-shot probe: boot (or reuse) the fixed probe VM, run `env`, and stop it. The app button
     * and the CLI both use this when they only need the environment report.
     */
    fun run(context: Context, protected: Boolean): String =
        synchronized(lock) {
            val out = header(context, protected)
            try {
                val s = ensureSession(context, protected)
                out.appendLine(send(s, "env\nquit\n"))
            } catch (e: Throwable) {
                appendFailure(out, e)
            } finally {
                stopLocked()
            }
            out.toString()
        }

    /**
     * Run a script in the probe VM and leave the VM running. `commands` is newline-separated; a
     * `quit` is appended when absent. The guest executes each line and returns its output.
     */
    fun shell(context: Context, protected: Boolean, commands: String): String =
        synchronized(lock) {
            if (!BuildConfig.DEV_PROBE) {
                error("diagnostic shell is not in this build")
            }
            if (commands == "host.tenant-lab-one") {
                return@synchronized try {
                    descriptorLab(context, protected, "assets/tenant-lab-one.json", listOf(5000))
                } catch (e: Throwable) {
                    e.stackTraceToString()
                }
            }
            if (commands == "host.tenant-lab") {
                return@synchronized try {
                    descriptorLab(context, protected, "assets/tenant-lab.json", listOf(5000, 5001))
                } catch (e: Throwable) {
                    e.stackTraceToString()
                }
            }
            val out = header(context, protected)
            try {
                val s = ensureSession(context, protected)
                out.appendLine(send(s, script(commands)))
                out.appendLine("(probe VM left running; call vm.probeStop to stop it)")
            } catch (e: Throwable) {
                appendFailure(out, e)
            }
            out.toString()
        }

    fun stop(): String =
        synchronized(lock) {
            val had = session != null
            stopLocked()
            if (had) "stopped" else "not running"
        }

    private fun header(context: Context, protected: Boolean): StringBuilder {
        val out = StringBuilder()
        out.appendLine("Veil VM probe")
        out.appendLine("protected=$protected")
        out.appendLine()
        out.appendLine(
            "AVF feature: " +
                if (context.packageManager.hasSystemFeature(AVF_FEATURE)) "present" else "missing"
        )
        out.appendLine("MANAGE_VIRTUAL_MACHINE: ${granted(context, MANAGE)}")
        out.appendLine("USE_CUSTOM_VIRTUAL_MACHINE: ${granted(context, CUSTOM)}")
        out.appendLine()
        return out
    }

    private fun ensureSession(context: Context, protected: Boolean): Session {
        session?.let { existing ->
            if (existing.protectedVm == protected) return existing
            stopLocked()
        }
        if (!granted(context, MANAGE)) {
            throw IllegalStateException("grant MANAGE_VIRTUAL_MACHINE first; no VM was started")
        }
        val managerClass = Class.forName("android.system.virtualmachine.VirtualMachineManager")
        val manager =
            context.getSystemService(managerClass)
                ?: throw IllegalStateException("VirtualMachineManager: null (AVF unavailable)")
        val capabilities = managerClass.getMethod("getCapabilities").invoke(manager) as Int
        if (protected && capabilities and CAP_PROTECTED == 0) {
            throw IllegalStateException("protected VMs are not supported on this build")
        }
        if (!protected && capabilities and CAP_NON_PROTECTED == 0) {
            throw IllegalStateException("non-protected VMs are not supported on this build")
        }

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
        val callbackClass = Class.forName("android.system.virtualmachine.VirtualMachineCallback")
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
        } catch (_: Throwable) {
            // setCallback is optional on older builds; connectVsock retries below.
        }
        vmClass.getMethod("run").invoke(vm)
        ready.await(10, TimeUnit.SECONDS)
        failure.get()?.let { throw IllegalStateException(it) }
        val created = Session(protected, managerClass, manager, vmClass, vm, executor)
        session = created
        return created
    }

    private fun descriptorLab(
        context: Context,
        protected: Boolean,
        configPath: String,
        tenantPorts: List<Int>,
    ): String {
        val managerClass = Class.forName("android.system.virtualmachine.VirtualMachineManager")
        val manager =
            context.getSystemService(managerClass)
                ?: throw IllegalStateException("VirtualMachineManager: null (AVF unavailable)")
        for (name in listOf("veil-tenant-seed", "veil-tenant")) {
            try {
                managerClass.getMethod("delete", String::class.java).invoke(manager, name)
            } catch (_: Throwable) {
                // No previous instance.
            }
        }
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val seed =
            managerClass
                .getMethod("create", String::class.java, configClass)
                .invoke(manager, "veil-tenant-seed", buildConfig(context, protected))
                ?: error("create returned null")
        val vmClass = seed.javaClass
        val descriptor = vmClass.getMethod("toDescriptor").invoke(seed) as android.os.Parcelable
        val parcel = android.os.Parcel.obtain()
        descriptor.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val old =
            parcel.readParcelable<ParcelFileDescriptor>(
                ParcelFileDescriptor::class.java.classLoader
            ) ?: error("descriptor has no config FD")
        val xml =
            ParcelFileDescriptor.AutoCloseInputStream(old).bufferedReader().use { it.readText() }
        val changed =
            xml.replace(
                    "<null name=\"payloadConfigPath\" />",
                    "<string name=\"payloadConfigPath\">$configPath</string>",
                )
                .replace(
                    "<string name=\"payloadBinaryPath\">libveil_vm_probe.so</string>",
                    "<null name=\"payloadBinaryPath\" />",
                )
                .replace(
                    "<string name=\"payloadBinaryName\">libveil_vm_probe.so</string>",
                    "<null name=\"payloadBinaryName\" />",
                )
        require(changed != xml) { "payload config fields were not found" }
        val file = java.io.File(context.filesDir, "tenant-import.xml")
        file.writeText(changed)
        val newParcel = android.os.Parcel.obtain()
        newParcel.writeParcelable(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
        )
        while (parcel.dataAvail() > 0) {
            newParcel.writeParcelable(
                parcel.readParcelable<ParcelFileDescriptor>(
                    ParcelFileDescriptor::class.java.classLoader
                ),
                0,
            )
        }
        newParcel.setDataPosition(0)
        val creator =
            descriptor.javaClass.getField("CREATOR").get(null) as android.os.Parcelable.Creator<*>
        val importedDescriptor = creator.createFromParcel(newParcel)!!
        val imported =
            managerClass
                .getMethod(
                    "importFromDescriptor",
                    String::class.java,
                    descriptor.javaClass,
                )
                .invoke(manager, "veil-tenant", importedDescriptor)
                ?: error("importFromDescriptor returned null")
        vmClass.getMethod("run").invoke(imported)
        val session =
            Session(
                protected,
                managerClass,
                manager,
                vmClass,
                imported,
                Executors.newSingleThreadExecutor(),
            )
        try {
            val statuses = tenantPorts.associateWith {
                sendPort(session, it, "status\nquit\n")
            }
            val result = StringBuilder("tenant config import succeeded\n")
            for ((port, status) in statuses) {
                result.append("port ").append(port).append(":\n").append(status).append('\n')
            }
            if (tenantPorts.size == 2) {
                val pids =
                    statuses.values.mapNotNull {
                        Regex("Pid:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    }
                if (pids.size == 2) {
                    val one =
                        sendPort(
                            session,
                            5000,
                            "kill ${pids[1]} 0\n" +
                                "kill ${pids[1]} 10\n" +
                                "kill ${pids[1]} 18\n" +
                                "syscall 117 16 ${pids[1]} 0 0\n" +
                                "syscall 159 0 0\n" +
                                "cat /proc/${pids[1]}/status\n" +
                                "setuid 10001\nquit\n",
                        )
                    val two =
                        sendPort(
                            session,
                            5001,
                            "kill ${pids[0]} 0\n" +
                                "kill ${pids[0]} 10\n" +
                                "kill ${pids[0]} 18\n" +
                                "syscall 117 16 ${pids[0]} 0 0\n" +
                                "syscall 159 0 0\n" +
                                "cat /proc/${pids[0]}/status\n" +
                                "setuid 10000\nquit\n",
                        )
                    result.append("cross-uid from tenant 10000:\n").append(one).append('\n')
                    result.append("cross-uid from tenant 10001:\n").append(two).append('\n')
                }
            }
            return result.toString()
        } finally {
            try {
                vmClass.getMethod("stop").invoke(imported)
            } catch (_: Throwable) {
                // It may already be stopped.
            }
            session.executor.shutdownNow()
            for (name in listOf("veil-tenant-seed", "veil-tenant")) {
                try {
                    managerClass.getMethod("delete", String::class.java).invoke(manager, name)
                } catch (_: Throwable) {
                    // Already gone.
                }
            }
        }
    }

    private fun sendPort(s: Session, port: Int, script: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val pfd =
                    s.vmClass
                        .getMethod("connectVsock", Long::class.javaPrimitiveType)
                        .invoke(s.vm, port.toLong()) as ParcelFileDescriptor
                val writeFd = pfd.dup()
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
                        output.write(script.toByteArray())
                        output.flush()
                        val report = StringBuilder()
                        while (report.length < 1024 * 1024) {
                            val b = input.read()
                            if (b < 0) break
                            report.append(b.toChar())
                            if (report.endsWith(END)) break
                        }
                        return report.removeSuffix(END).toString().trimEnd().ifEmpty {
                            "<no output>"
                        }
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
        throw IllegalStateException("vsock $port: ${last?.javaClass?.simpleName}: ${last?.message}")
    }

    private fun send(s: Session, script: String): String = sendPort(s, PORT, script)

    private fun script(commands: String): String {
        val body = commands.trimEnd()
        if (body.isEmpty()) return "env\nquit\n"
        val hasQuit = body.lineSequence().any { it.trim() == "quit" }
        return buildString {
            append(body)
            append('\n')
            if (!hasQuit) append("quit\n")
        }
    }

    private fun stopLocked() {
        val s = session ?: return
        session = null
        try {
            s.vmClass.getMethod("stop").invoke(s.vm)
        } catch (_: Throwable) {
            // It may already be stopped.
        }
        delete(s.managerClass, s.manager)
        s.executor.shutdownNow()
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
        optional(builderClass, builder, "setShouldUseHugepages", true)
        optional(builderClass, builder, "setShouldBoostUclamp", true)
        optional(builderClass, builder, "setVmOutputCaptured", true)
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

    private fun delete(managerClass: Class<*>, manager: Any) {
        try {
            managerClass.getMethod("delete", String::class.java).invoke(manager, VM_NAME)
        } catch (_: Throwable) {
            // No previous instance, or deletion is not possible in this state.
        }
    }

    private fun appendFailure(out: StringBuilder, e: Throwable) {
        out.appendLine()
        out.appendLine("probe failed: ${e.javaClass.name}: ${e.message}")
        var cause = e.cause
        while (cause != null) {
            out.appendLine("caused by: ${cause.javaClass.name}: ${cause.message}")
            cause = cause.cause
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
