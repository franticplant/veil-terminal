package io.github.franticplant.veil.dev.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTest {
    private val spec =
        method(
            "sessions.input",
            Scope.OPERATE,
            "Types into a session.",
            true,
            long("id", "Session.", required = true, min = 1),
            str("text", "Text."),
            int("repeat", "Times.", min = 1, max = 10),
            bool("enter", "Then Enter."),
            str("decision", "How.", choices = listOf("once", "remember")),
            strList("responses", "Answers.", secret = true),
        )

    private fun error(reply: JSONObject) = reply.getJSONObject("error").getString("code")

    @Test
    fun convertsCommandLineStrings() {
        val p =
            spec.check(
                JSONObject()
                    .put("id", "7")
                    .put("text", "ls")
                    .put("repeat", "3")
                    .put("enter", "yes")
                    .put("responses", "hunter2")
            )
        assertEquals(7L, p.long("id"))
        assertEquals(3, p.int("repeat"))
        assertTrue(p.boolOr("enter", false))
        assertEquals(listOf("hunter2"), p.stringList("responses"))
        assertTrue(p.hasSecret)
        assertEquals(setOf("id", "text", "repeat", "enter", "responses"), p.names)
    }

    @Test
    fun acceptsJsonTypes() {
        val p =
            spec.check(
                JSONObject()
                    .put("id", 7)
                    .put("enter", true)
                    .put("responses", JSONArray(listOf("a", "b")))
            )
        assertEquals(7L, p.long("id"))
        assertEquals(listOf("a", "b"), p.stringList("responses"))
        assertFalse(p.has("text"))
    }

    @Test
    fun refusesUnknownMissingAndOutOfRange() {
        val t = MethodTable()
        t.add(spec) { "ran" }
        assertEquals("ran", t.call(spec.name, JSONObject().put("id", 1)).get("result"))
        assertEquals("invalid_params", error(t.call(spec.name, JSONObject())))
        assertEquals(
            "invalid_params",
            error(t.call(spec.name, JSONObject().put("id", 1).put("txt", "typo"))),
        )
        assertEquals("invalid_params", error(t.call(spec.name, JSONObject().put("id", 0))))
        assertEquals(
            "invalid_params",
            error(t.call(spec.name, JSONObject().put("id", 1).put("repeat", 11))),
        )
        assertEquals(
            "invalid_params",
            error(t.call(spec.name, JSONObject().put("id", 1).put("decision", "always"))),
        )
        assertEquals(
            "invalid_params",
            error(t.call(spec.name, JSONObject().put("id", 1.5))),
        )
        assertEquals("unknown_method", error(t.call("nope", JSONObject())))
    }

    @Test
    fun catalogRoundTrips() {
        val back = MethodSpec.parseCatalog(MethodSpec.catalogJson(listOf(spec)))
        assertEquals(listOf(spec), back)
    }

    @Test
    fun implementationErrorsBecomeReplies() {
        val t = MethodTable()
        t.add(method("x", Scope.READ, "")) { throw ApiException(ErrorCode.NOT_FOUND, "gone") }
        t.add(method("y", Scope.READ, "")) { throw IllegalStateException("boom") }
        assertEquals("not_found", error(t.call("x", JSONObject())))
        assertEquals("internal", error(t.call("y", JSONObject())))
    }
}
