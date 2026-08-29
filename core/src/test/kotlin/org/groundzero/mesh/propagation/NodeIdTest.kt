package org.groundzero.mesh.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NodeIdTest {

    @Test
    fun canonicalRoundTrips() {
        val id = NodeId.parse("a8f3-92b1-4c12")
        assertEquals("a8f3-92b1-4c12", id.canonical())
        assertEquals(id, NodeId.parse(id.canonical()))
    }

    @Test
    fun zeroIsCanonical() {
        assertEquals("0000-0000-0000", NodeId(0).canonical())
    }

    @Test
    fun maxIs48Bit() {
        assertEquals("ffff-ffff-ffff", NodeId(NodeId.MAX_VALUE).canonical())
    }

    @Test
    fun rejectsAbove48Bit() {
        assertFailsWith<IllegalArgumentException> { NodeId(NodeId.MAX_VALUE + 1) }
    }

    @Test
    fun rejectsMalformedText() {
        assertFailsWith<IllegalArgumentException> { NodeId.parse("nope") }
        assertFailsWith<IllegalArgumentException> { NodeId.parse("a8f3-92b1") }
    }

    @Test
    fun randomStaysInRange() {
        repeat(1000) {
            val v = NodeId.random().value
            check(v in 0..NodeId.MAX_VALUE)
        }
    }
}
