package io.github.franticplant.veil.devd

import android.content.pm.PackageManager
import io.github.franticplant.veil.dev.api.ApiException
import io.github.franticplant.veil.dev.api.DevIds
import io.github.franticplant.veil.dev.api.ErrorCode
import io.github.franticplant.veil.dev.api.MethodSpec
import io.github.franticplant.veil.dev.api.MethodTable
import io.github.franticplant.veil.dev.api.ProcSelf
import io.github.franticplant.veil.dev.api.Scope
import io.github.franticplant.veil.dev.api.int
import io.github.franticplant.veil.dev.api.long
import io.github.franticplant.veil.dev.api.method
import io.github.franticplant.veil.dev.api.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * The methods devd implements itself: its own state, packages, updates and app lifecycle. Every
 * other method is Veil's, forwarded through DevHooks.
 *
 * Deliberately absent: anything that changes devd's own configuration, pairings or access log.
 * Those are managed on the phone only.
 */
class DevdMethods(private val app: DevdApp) {
    val table = MethodTable()

    /** Callable before pairing; listed for completeness, handled by the server. */
    private val preAuth =
        listOf(
            method(
                "devd.hello",
                Scope.READ,
                "Who devd is, its certificate digest, and whether this client is paired. Needs no pairing.",
            ),
            method(
                "pair.request",
                Scope.READ,
                "Asks the user on the phone to pair this client; blocks until they decide (2 minutes). " +
                    "Needs no pairing.",
                true,
                str("name", "A name for this client, shown on the phone."),
                str("scopes", "The scopes asked for (a JSON array); the user may grant fewer."),
            ),
        )

    init {
        table.add(
            method(
                "devd.info",
                Scope.READ,
                "devd's version, listener, auto-off time, this client's pairing, and the Veil package.",
            )
        ) {
            val v = app.settings.values.value
            val caller = Caller.current.get()
            JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("serverCertSha256", app.server.fingerprint)
                .put(
                    "listener",
                    JSONObject()
                        .put("addresses", JSONArray(app.server.addresses.value))
                        .put("port", v.port)
                        .put("loopback", v.loopback)
                        .put("interfaces", JSONArray(v.ifaces.sorted()))
                        .put(
                            "binds",
                            JSONArray().apply {
                                for (b in app.server.binds.value) {
                                    put(
                                        JSONObject()
                                            .put("interface", b.iface)
                                            .put("address", b.address ?: JSONObject.NULL)
                                            .put("state", b.state)
                                            .put("detail", b.detail)
                                    )
                                }
                            },
                        )
                        .put("offAtMs", app.offAtMs.value ?: JSONObject.NULL)
                        .put("connections", app.server.activeConnections),
                )
                .put(
                    "caller",
                    if (caller == null) JSONObject.NULL
                    else
                        JSONObject()
                            .put("name", caller.name)
                            .put("scopes", JSONArray(caller.scopes.sorted()))
                            .put("expiresMs", caller.expiresMs),
                )
                .put(
                    "veil",
                    packageJson(DevIds.VEIL_PACKAGE).put("devHooksBound", app.hooks.connected),
                )
                .put("canInstall", app.packageManager.canRequestPackageInstalls())
        }

        table.add(
            method(
                "methods",
                Scope.READ,
                "Every method: devd's and, if Veil's dev build is reachable, Veil's. Marks those this " +
                    "pairing may call.",
            )
        ) {
            val scopes = Caller.current.get()?.scopes.orEmpty()
            val veil =
                try {
                    app.hooks.catalog()
                } catch (e: ApiException) {
                    null
                }
            fun list(specs: List<MethodSpec>, owner: String) = specs.map {
                it.toJson().put("owner", owner).put("allowed", it.scope in scopes)
            }
            JSONObject()
                .put("veilAvailable", veil != null)
                .put(
                    "methods",
                    JSONArray(
                        list(preAuth, "devd").map { it.put("allowed", true).put("preAuth", true) } +
                            list(table.specs, "devd") +
                            list(veil.orEmpty(), "veil")
                    ),
                )
        }

        table.add(
            method(
                "devd.logs",
                Scope.READ,
                "devd's journal: its log (listener, pairing, updates, hooks) and, with kind=access or " +
                    "all, the access log. Persistent and hash-chained; oldest first after a cursor.",
                false,
                long("after", "Entries with seq greater than this (default 0).", min = 0),
                int("max", "At most this many (default 200).", min = 1, max = 1000),
                str(
                    "kind",
                    "Which entries (default log).",
                    choices = listOf("log", "access", "all"),
                ),
                str("level", "Minimum level.", choices = listOf("debug", "info", "warn", "error")),
                str("component", "Only this component."),
                str("grep", "Only entries containing this text (case-insensitive)."),
            )
        ) { p ->
            app.journal.read(
                p.longOr("after", 0),
                p.intOr("max", 200),
                JournalFilter.of(
                    p.stringOr("kind", "log"),
                    if (p.has("level")) p.string("level") else null,
                    if (p.has("component")) p.string("component") else null,
                    if (p.has("grep")) p.string("grep") else null,
                ),
            )
        }

        table.add(
            method(
                "devd.accessLog",
                Scope.READ,
                "The newest API calls: client, method, parameter names (never values), outcome, " +
                    "duration, and the listener and peer addresses.",
                false,
                int("max", "At most this many, oldest first (default 100).", min = 1, max = 1000),
            )
        ) { p ->
            app.journal.tail(p.intOr("max", 100), JournalFilter.of("access"))
        }

        table.add(
            method(
                "metrics.current",
                Scope.READ,
                "The newest sample: devd, Veil's app and engine processes (RAM, CPU %, threads, " +
                    "fds) and each connection (peer, wire bytes, rates). Sampled every 2 s while the " +
                    "listener runs.",
            )
        ) {
            app.metrics.latest.value ?: JSONObject().put("unixMs", JSONObject.NULL)
        }

        table.add(
            method(
                "devd.threads",
                Scope.READ,
                "devd's live threads: name, state, and the top of each stack (for leak hunting).",
            )
        ) {
            JSONArray().apply {
                for ((t, stack) in Thread.getAllStackTraces().entries.sortedBy { it.key.name }) {
                    put(
                        JSONObject()
                            .put("name", t.name)
                            .put("state", t.state.name)
                            .put("daemon", t.isDaemon)
                            .put("top", JSONArray(stack.take(6).map { it.toString() }))
                    )
                }
            }
        }

        table.add(
            method(
                "devd.smaps",
                Scope.READ,
                "Where devd's memory lives: PSS by mapping (boot image, heap spaces, code, " +
                    "native allocator), largest first.",
                false,
                int("top", "How many groups to return (default 40).", min = 1, max = 500),
            )
        ) { p ->
            ProcSelf.smaps(p.intOr("top", 40))
        }

        table.add(method("metrics.keys", Scope.READ, "Names of the recorded time series.")) {
            JSONArray(app.metrics.keys())
        }

        table.add(
            method(
                "metrics.history",
                Scope.READ,
                "Time series ([unixMs, value] pairs, up to 30 minutes), e.g. veil-engine.rssKb, " +
                    "veil-app.cpuPct, net.inBps, session.3.outBps.",
                false,
                io.github.franticplant.veil.dev.api.strList(
                    "prefixes",
                    "Only series starting with these.",
                ),
                long("sinceMs", "Only points at or after this time (unix ms).", min = 0),
            )
        ) { p ->
            app.metrics.history(p.stringListOr("prefixes").orEmpty(), p.longOr("sinceMs", 0))
        }

        table.add(
            method(
                "journal.head",
                Scope.READ,
                "The journal's newest seq and hash. Keep it: a later journal.verify plus a read of " +
                    "that seq shows whether anything up to it was rewritten or cut.",
            )
        ) {
            app.journal.head()
        }

        table.add(
            method(
                "journal.verify",
                Scope.READ,
                "Walks the whole hash chain and reports the first break, if any.",
            )
        ) {
            app.journal.verify()
        }

        table.add(
            method(
                "packages.list",
                Scope.READ,
                "Veil's packages on this phone: versions, signer, whether it is the pinned Veil key, installer.",
            )
        ) {
            JSONArray().apply { DevIds.VEIL_PACKAGES.sorted().forEach { put(packageJson(it)) } }
        }

        table.add(
            method(
                "update.begin",
                Scope.UPDATE,
                "Starts an upload of one APK. Then update.chunk, then update.commit.",
                true,
                long("size", "APK size in bytes.", required = true, min = 1),
                str("sha256", "The APK's SHA-256, hex.", required = true),
            )
        ) { p ->
            app.updater.begin(p.long("size"), p.string("sha256").lowercase())
        }

        table.add(
            method(
                "update.chunk",
                Scope.UPDATE,
                "Appends base64 data at offset (must equal the bytes received so far).",
                true,
                str("uploadId", "From update.begin.", required = true),
                long("offset", "Byte offset.", required = true, min = 0),
                str("data", "Base64, at most 1 MiB decoded.", required = true),
            )
        ) { p ->
            app.updater.chunk(p.string("uploadId"), p.long("offset"), p.string("data"))
        }

        table.add(
            method(
                "update.commit",
                Scope.UPDATE,
                "Verifies the upload (digest, Veil package, pinned signer, no downgrade) and installs it. " +
                    "Returns the install record; follow with update.status.",
                true,
                str("uploadId", "From update.begin.", required = true),
            )
        ) { p ->
            app.updater.commit(p.string("uploadId"))
        }

        table.add(method("update.status", Scope.UPDATE, "Recent installs, newest first.")) {
            app.updater.status()
        }

        table.add(
            method(
                "app.start",
                Scope.LIFECYCLE,
                "Makes sure Veil is running (binding to DevHooks starts its process) and returns app.info.",
                true,
            )
        ) {
            app.hooks.get(15_000)
            result(app.hooks.call("app.info", JSONObject()))
        }

        table.add(
            method(
                "app.restart",
                Scope.LIFECYCLE,
                "Ends the Veil process and waits until it is back (a new pid). Sessions end.",
                true,
                long(
                    "timeoutMs",
                    "How long to wait for the new process (default 20000).",
                    min = 1000,
                    max = 120_000,
                ),
            )
        ) { p ->
            val t0 = System.currentTimeMillis()
            val old = result(app.hooks.call("app.exit", JSONObject())).getInt("pid")
            val deadline = t0 + p.longOr("timeoutMs", 20_000)
            var info: JSONObject? = null
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(300)
                info =
                    try {
                        result(app.hooks.call("app.info", JSONObject()))
                    } catch (e: ApiException) {
                        null
                    }
                if (info != null && info.getInt("pid") != old) break
                info = null
            }
            info ?: throw ApiException(ErrorCode.TIMEOUT, "Veil did not come back")
            JSONObject()
                .put("oldPid", old)
                .put("newPid", info.getInt("pid"))
                .put("ms", System.currentTimeMillis() - t0)
        }
    }

    private fun result(reply: JSONObject): JSONObject {
        reply.optJSONObject("error")?.let {
            throw ApiException(it.getString("code"), it.getString("message"))
        }
        return reply.getJSONObject("result")
    }

    private fun packageJson(pkg: String): JSONObject {
        val info =
            app.updater.installed(pkg)
                ?: return JSONObject().put("package", pkg).put("installed", false)
        val signers =
            info.signingInfo?.apkContentsSigners.orEmpty().map { Tls.sha256Hex(it.toByteArray()) }
        val source =
            try {
                app.packageManager.getInstallSourceInfo(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        return JSONObject()
            .put("package", pkg)
            .put("installed", true)
            .put("versionName", info.versionName)
            .put("versionCode", info.longVersionCode)
            .put("dev", info.versionName?.endsWith("-dev") == true)
            .put("signers", JSONArray(signers))
            .put(
                "pinnedSigner",
                signers.isNotEmpty() && signers.all { it == BuildConfig.PINNED_CERT_SHA256 },
            )
            .put("installer", source?.installingPackageName ?: JSONObject.NULL)
            .put("updateOwner", source?.updateOwnerPackageName ?: JSONObject.NULL)
            .put("firstInstallMs", info.firstInstallTime)
            .put("lastUpdateMs", info.lastUpdateTime)
            .put("enabled", info.applicationInfo?.enabled ?: false)
    }
}
