package io.github.franticplant.veil.dev.api

import org.json.JSONObject

/**
 * A set of methods with their implementations. The dev agent and the app's DevHooks each build one;
 * [call] checks parameters against the spec before running anything.
 */
class MethodTable {
    private val methods = LinkedHashMap<String, Pair<MethodSpec, (Params) -> Any?>>()

    val specs: List<MethodSpec>
        get() = methods.values.map { it.first }

    fun add(spec: MethodSpec, impl: (Params) -> Any?) {
        require(spec.name !in methods) { "duplicate method ${spec.name}" }
        require(spec.scope in Scope.ALL) { "${spec.name}: unknown scope ${spec.scope}" }
        methods[spec.name] = spec to impl
    }

    operator fun contains(name: String) = name in methods

    fun spec(name: String): MethodSpec? = methods[name]?.first

    /** Runs [name] with [raw] parameters; returns the reply object ({"result"} or {"error"}). */
    fun call(name: String, raw: JSONObject): JSONObject {
        val (spec, impl) =
            methods[name] ?: return Reply.error(ErrorCode.UNKNOWN_METHOD, "no method $name")
        return try {
            Reply.ok(impl(spec.check(raw)))
        } catch (e: ApiException) {
            Reply.error(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Reply.error(ErrorCode.INTERNAL, "interrupted")
        } catch (e: RuntimeException) {
            Reply.error(ErrorCode.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

/** Builds a [MethodSpec] with less noise. */
fun method(
    name: String,
    scope: String,
    description: String,
    mutates: Boolean = false,
    vararg params: Param,
) = MethodSpec(name, scope, description, params.toList(), mutates)

fun str(
    name: String,
    description: String,
    required: Boolean = false,
    secret: Boolean = false,
    choices: List<String> = emptyList(),
) = Param(name, Type.STRING, description, required, secret, choices)

fun int(
    name: String,
    description: String,
    required: Boolean = false,
    min: Long? = null,
    max: Long? = null,
) = Param(name, Type.INT, description, required, min = min, max = max)

fun long(
    name: String,
    description: String,
    required: Boolean = false,
    min: Long? = null,
    max: Long? = null,
) = Param(name, Type.LONG, description, required, min = min, max = max)

fun bool(name: String, description: String) = Param(name, Type.BOOL, description)

fun strList(name: String, description: String, required: Boolean = false, secret: Boolean = false) =
    Param(name, Type.STRING_LIST, description, required, secret)
