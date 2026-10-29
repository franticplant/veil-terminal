package io.github.franticplant.veil.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Host keys the frontend remembers itself.
 *
 * A guest engine cannot read Android app storage, so the Microdroid payload always reports an
 * unknown host key. This store is the Android-side policy: the registry answers a matching pin
 * without a prompt, refuses a changed one, and records a key only after the user chooses
 * "Trust and remember".
 *
 * Only the algorithm and SHA-256 fingerprint are stored. The full public key never needs to enter
 * the VM, and a fingerprint comparison is enough to recognise the key.
 */
class KnownHostsStore(
    private val file: File,
    private val log: LogRing? = null,
) {
    data class Pin(
        val host: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String,
    )

    sealed interface Check {
        data class Known(val pin: Pin) : Check

        data class Changed(val pin: Pin) : Check

        data object Unknown : Check
    }

    private val pins = LinkedHashMap<String, Pin>()

    init {
        synchronized(this) { load() }
    }

    @Synchronized
    fun check(host: String, port: Int, algorithm: String, fingerprint: String): Check {
        val pin = pins[key(host, port)] ?: return Check.Unknown
        return if (pin.algorithm == algorithm && pin.fingerprint == fingerprint) {
            Check.Known(pin)
        } else {
            Check.Changed(pin)
        }
    }

    /** Records or replaces the pin for host:port. Throws if the file cannot be written. */
    @Synchronized
    fun remember(host: String, port: Int, algorithm: String, fingerprint: String): Pin {
        val pin = Pin(host, port, algorithm, fingerprint)
        val old = pins.put(key(host, port), pin)
        try {
            save()
        } catch (e: Exception) {
            if (old != null) pins[key(host, port)] = old else pins.remove(key(host, port))
            throw e
        }
        log?.info("hostkeys", "remembered $algorithm $fingerprint for $host:$port")
        return pin
    }

    /** Removes the pin for host:port; returns how many were removed. */
    @Synchronized
    fun forget(host: String, port: Int): Int {
        val removed = pins.remove(key(host, port)) != null
        if (removed) {
            save()
            log?.info("hostkeys", "forgot the key of $host:$port")
        }
        return if (removed) 1 else 0
    }

    @Synchronized
    fun list(): List<Pin> = pins.values.sortedWith(compareBy({ it.host }, { it.port }))

    private fun key(host: String, port: Int) = "$host\u0000$port"

    private fun load() {
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val fields = line.split('\t')
                if (fields.size != 4) {
                    log?.warn("hostkeys", "ignored malformed frontend known_hosts line")
                    return@forEachLine
                }
                val port = fields[1].toIntOrNull()
                if (port == null) {
                    log?.warn("hostkeys", "ignored frontend known_hosts line with a bad port")
                    return@forEachLine
                }
                val pin = Pin(fields[0], port, fields[2], fields[3])
                pins[key(pin.host, pin.port)] = pin
            }
            if (pins.isNotEmpty()) {
                log?.info("hostkeys", "loaded ${pins.size} remembered host key(s)")
            }
        } catch (e: Exception) {
            log?.warn("hostkeys", "could not read remembered host keys: ${e.message}")
        }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val text =
            buildString {
                appendLine("# Veil frontend host-key pins (algorithm + SHA-256 fingerprint)")
                for (pin in list()) {
                    append(pin.host)
                    append('\t')
                    append(pin.port)
                    append('\t')
                    append(pin.algorithm)
                    append('\t')
                    append(pin.fingerprint)
                    append('\n')
                }
            }
        tmp.writeText(text)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (!file.isFile) throw IOException("host key file was not written: $file")
    }
}
