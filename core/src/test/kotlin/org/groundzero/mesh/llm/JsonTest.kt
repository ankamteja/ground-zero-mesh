package org.groundzero.mesh.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {

    @Test
    fun `parses the nested shapes a snapshot actually has`() {
        val obj = Json.asObject(
            Json.parse(
                """{"advice":"two incidents","clusters":[{"zone":"floor 2","minHops":3,"placed":true,
                   "gpsLat":null,"priority":0.87,"evidence":["rushing water","pinned"]}],
                   "self":{"nodeId":"0000-0000-00d5"}}""",
            ),
        )
        assertEquals("two incidents", Json.str(obj, "advice"))
        val first = Json.asObject(Json.asList(obj?.get("clusters")).first())
        assertEquals("floor 2", Json.str(first, "zone"))
        assertEquals(3, Json.int(first, "minHops"))
        assertEquals(true, Json.bool(first, "placed"))
        assertEquals(0.87, Json.num(first, "priority"))
        assertEquals(listOf("rushing water", "pinned"), Json.strList(first, "evidence"))
        assertEquals("0000-0000-00d5", Json.str(Json.asObject(obj?.get("self")), "nodeId"))
    }

    @Test
    fun `a JSON null and an absent key are both null, not zero`() {
        val obj = Json.asObject(Json.parse("""{"gpsLat":null}"""))
        assertNull(Json.num(obj, "gpsLat"))
        assertNull(Json.num(obj, "gpsLon"))
        assertNull(Json.int(obj, "recommendedActionRank"))
    }

    @Test
    fun `round-trips the characters a model answer contains`() {
        val text = "line one\nline \"two\"\tand a backslash \\ end"
        val parsed = Json.asObject(Json.parse("{" + Json.field("answer", text) + "}"))
        assertEquals(text, Json.str(parsed, "answer"))
    }

    @Test
    fun `escapes control characters rather than emitting raw bytes`() {
        val quoted = Json.quote("a\u0001b")
        assertTrue(quoted.contains("\\u0001"), quoted)
        assertEquals("a\u0001b", Json.str(Json.asObject(Json.parse("{\"k\":$quoted}")), "k"))
    }

    @Test
    fun `empty containers parse`() {
        assertEquals(emptyMap<String, Any?>(), Json.asObject(Json.parse("{}")))
        assertEquals(emptyList<Any?>(), Json.asList(Json.parse("[]")))
    }
}
