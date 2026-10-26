package io.github.franticplant.veil.dev.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a paired client may do. A pairing is granted a set of scopes when the user approves it on
 * the phone; every method requires exactly one scope, and a secret parameter additionally requires
 * [SECRETS].
 */
object Scope {
    /** State, health, metrics, logs: nothing that shows terminal content or changes anything. */
    const val READ = "read"

    /** Terminal content (screen, scrollback). Can contain anything the remote printed. */
    const val READ_CONTENT = "read.content"

    /** Sessions, input, profiles, host keys: driving the app as a user would. */
    const val OPERATE = "operate"

    /** Passing credentials (passwords, auth responses). Values are never logged. */
    const val SECRETS = "secrets"

    /** Restarting the engine or the app. */
    const val LIFECYCLE = "lifecycle"

    /** Built-in test scenarios. */
    const val SCENARIO = "scenario"

    /** Uploading and installing builds of Veil packages. */
    const val UPDATE = "update"

    val ALL = listOf(READ, READ_CONTENT, OPERATE, SECRETS, LIFECYCLE, SCENARIO, UPDATE)
}

/** Parameter types. Values arriving as strings (from a command line) are converted. */
enum class Type(val wire: String) {
    STRING("string"),
    INT("int"),
    LONG("long"),
    BOOL("bool"),
    STRING_LIST("string[]");

    companion object {
        fun of(wire: String): Type =
            entries.firstOrNull { it.wire == wire }
                ?: throw ApiException(ErrorCode.INVALID_PARAMS, "unknown type $wire")
    }
}

/**
 * One parameter. [secret] values are redacted in logs, and supplying one needs [Scope.SECRETS].
 * [choices], when set, lists the accepted values of a STRING.
 */
data class Param(
    val name: String,
    val type: Type,
    val description: String,
    val required: Boolean = false,
    val secret: Boolean = false,
    val choices: List<String> = emptyList(),
    val min: Long? = null,
    val max: Long? = null,
)

/** A method of the dev API. [mutates] is informational: whether calling it changes state. */
data class MethodSpec(
    val name: String,
    val scope: String,
    val description: String,
    val params: List<Param> = emptyList(),
    val mutates: Boolean = false,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("name", name)
            .put("scope", scope)
            .put("description", description)
            .put("mutates", mutates)
            .put(
                "params",
                JSONArray().apply {
                    for (p in params) {
                        val o =
                            JSONObject()
                                .put("name", p.name)
                                .put("type", p.type.wire)
                                .put("description", p.description)
                                .put("required", p.required)
                                .put("secret", p.secret)
                        if (p.choices.isNotEmpty()) o.put("choices", JSONArray(p.choices))
                        p.min?.let { o.put("min", it) }
                        p.max?.let { o.put("max", it) }
                        put(o)
                    }
                },
            )

    /**
     * Checks [raw] against the spec and returns the parameters with their declared types. Unknown
     * parameters are refused, not ignored, so a typo never silently means "default".
     */
    fun check(raw: JSONObject): Params {
        val byName = params.associateBy { it.name }
        for (key in raw.keys()) {
            if (key !in byName) {
                throw ApiException(
                    ErrorCode.INVALID_PARAMS,
                    "$name has no parameter '$key' (it takes: ${params.joinToString { it.name }.ifEmpty { "none" }})",
                )
            }
        }
        val out = HashMap<String, Any>()
        for (p in params) {
            if (!raw.has(p.name) || raw.isNull(p.name)) {
                if (p.required) {
                    throw ApiException(ErrorCode.INVALID_PARAMS, "$name needs '${p.name}'")
                }
                continue
            }
            out[p.name] = convert(p, raw.get(p.name))
        }
        return Params(this, out)
    }

    private fun convert(p: Param, v: Any): Any {
        fun bad(why: String): Nothing =
            throw ApiException(ErrorCode.INVALID_PARAMS, "$name: '${p.name}' $why")

        val value: Any =
            when (p.type) {
                Type.STRING -> v as? String ?: v.toString()
                Type.INT,
                Type.LONG -> {
                    val n =
                        when (v) {
                            is Int -> v.toLong()
                            is Long -> v
                            is Number ->
                                v.toDouble().let { d ->
                                    if (d % 1.0 != 0.0) bad("must be a whole number")
                                    d.toLong()
                                }
                            is String -> v.trim().toLongOrNull() ?: bad("must be a number")
                            else -> bad("must be a number")
                        }
                    p.min?.let { if (n < it) bad("must be at least $it") }
                    p.max?.let { if (n > it) bad("must be at most $it") }
                    if (p.type == Type.INT) {
                        if (n !in Int.MIN_VALUE..Int.MAX_VALUE) bad("is out of range")
                        n.toInt()
                    } else n
                }
                Type.BOOL ->
                    when (v) {
                        is Boolean -> v
                        "true",
                        "1",
                        "yes" -> true
                        "false",
                        "0",
                        "no" -> false
                        else -> bad("must be true or false")
                    }
                Type.STRING_LIST ->
                    when (v) {
                        is JSONArray -> (0 until v.length()).map { v.get(it).toString() }
                        // From a command line: a JSON array, or one plain value.
                        is String ->
                            if (v.trimStart().startsWith("[")) {
                                val a = JSONArray(v)
                                (0 until a.length()).map { a.get(it).toString() }
                            } else listOf(v)
                        else -> bad("must be a list of strings")
                    }
            }
        if (p.choices.isNotEmpty() && value is String && value !in p.choices) {
            bad("must be one of ${p.choices.joinToString()}")
        }
        if (value is String && value.length > MAX_STRING) bad("is longer than $MAX_STRING")
        return value
    }

    companion object {
        /** The longest string parameter accepted. */
        const val MAX_STRING = 256 * 1024

        fun fromJson(o: JSONObject): MethodSpec {
            val ps = o.optJSONArray("params") ?: JSONArray()
            return MethodSpec(
                name = o.getString("name"),
                scope = o.getString("scope"),
                description = o.optString("description"),
                mutates = o.optBoolean("mutates"),
                params =
                    (0 until ps.length()).map { i ->
                        val p = ps.getJSONObject(i)
                        val choices = p.optJSONArray("choices")
                        Param(
                            name = p.getString("name"),
                            type = Type.of(p.getString("type")),
                            description = p.optString("description"),
                            required = p.optBoolean("required"),
                            secret = p.optBoolean("secret"),
                            choices =
                                if (choices == null) emptyList()
                                else (0 until choices.length()).map { choices.getString(it) },
                            min = if (p.has("min")) p.getLong("min") else null,
                            max = if (p.has("max")) p.getLong("max") else null,
                        )
                    },
            )
        }

        fun catalogJson(specs: List<MethodSpec>): String =
            JSONArray().apply { specs.forEach { put(it.toJson()) } }.toString()

        fun parseCatalog(json: String): List<MethodSpec> {
            val a = JSONArray(json)
            return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }
    }
}

/** Checked parameters of one call. */
class Params(val spec: MethodSpec, private val values: Map<String, Any>) {
    fun has(name: String) = name in values

    fun string(name: String): String = get(name) as String

    fun stringOr(name: String, default: String): String = values[name] as String? ?: default

    fun int(name: String): Int = get(name) as Int

    fun intOr(name: String, default: Int): Int = values[name] as Int? ?: default

    fun long(name: String): Long = get(name) as Long

    fun longOr(name: String, default: Long): Long = values[name] as Long? ?: default

    fun boolOr(name: String, default: Boolean): Boolean = values[name] as Boolean? ?: default

    @Suppress("UNCHECKED_CAST")
    fun stringList(name: String): List<String> = get(name) as List<String>

    @Suppress("UNCHECKED_CAST")
    fun stringListOr(name: String): List<String>? = values[name] as List<String>?

    /** Names of the parameters supplied, for access logs (never values). */
    val names: Set<String>
        get() = values.keys

    /** Whether any supplied parameter is secret. */
    val hasSecret: Boolean
        get() = spec.params.any { it.secret && it.name in values }

    /** The values as JSON again, for forwarding. */
    fun toJson(): JSONObject =
        JSONObject().apply {
            for ((k, v) in values) put(k, if (v is List<*>) JSONArray(v) else v)
        }

    private fun get(name: String): Any =
        values[name] ?: throw ApiException(ErrorCode.INVALID_PARAMS, "missing '$name'")
}

/** Error codes on the wire. */
object ErrorCode {
    /** The client is not paired, or its pairing expired or was revoked. */
    const val UNAUTHORIZED = "unauthorized"

    /** The pairing lacks the scope this needs. */
    const val FORBIDDEN = "forbidden"
    const val UNKNOWN_METHOD = "unknown_method"
    const val INVALID_PARAMS = "invalid_params"

    /** The thing operated on does not exist (a session, an upload). */
    const val NOT_FOUND = "not_found"

    /** The method's implementer is not reachable (e.g. Veil is a prod build, or not installed). */
    const val UNAVAILABLE = "unavailable"

    /** The operation ran and failed; the message says why. */
    const val FAILED = "failed"
    const val TIMEOUT = "timeout"
    const val RATE_LIMITED = "rate_limited"
    const val INTERNAL = "internal"
}

class ApiException(val code: String, message: String) : Exception(message)

/** Encodes the reply of [IDevHooks.call] and of the wire protocol. */
object Reply {
    fun ok(result: Any?): JSONObject = JSONObject().put("result", result ?: JSONObject.NULL)

    fun error(code: String, message: String): JSONObject =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message))

    fun error(e: ApiException): JSONObject = error(e.code, e.message ?: e.code)
}

/** Package identities and the permission that ties the dev plane together. */
object DevIds {
    /** The Veil app (dev and prod flavors share it). */
    const val VEIL_PACKAGE = "io.github.franticplant.veil"

    /** The dev agent. */
    const val DEVD_PACKAGE = "io.github.franticplant.veil.devd"

    /** Signature-level: only packages signed with the Veil key hold it. */
    const val DEV_HOOKS_PERMISSION = "io.github.franticplant.veil.permission.DEV_HOOKS"

    /** The bound service in Veil's dev flavor. */
    const val DEV_HOOKS_SERVICE = "io.github.franticplant.veil.dev.DevHooksService"

    /** Every package devd may install or update. */
    val VEIL_PACKAGES = setOf(VEIL_PACKAGE, DEVD_PACKAGE)
}
