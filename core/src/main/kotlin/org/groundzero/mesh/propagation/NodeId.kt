package org.groundzero.mesh.propagation

import kotlin.random.Random

/**
 * A 48-bit mesh node identifier. Canonical text form is three lowercase 4-hex groups,
 * e.g. `a8f3-92b1-4c12`.
 *
 * 48 bits is deliberate: it fits a MAC-style address, packs into 6 bytes on a LoRa frame,
 * and gives enough space that random assignment across a district collides negligibly.
 */
@JvmInline
value class NodeId(val value: Long) {

    init {
        require(value in 0L..MAX_VALUE) { "NodeId must be 48-bit, got $value" }
    }

    /** `a8f3-92b1-4c12` */
    fun canonical(): String {
        val hi = (value ushr 32) and 0xFFFF
        val mid = (value ushr 16) and 0xFFFF
        val lo = value and 0xFFFF
        return "%04x-%04x-%04x".format(hi, mid, lo)
    }

    override fun toString(): String = canonical()

    companion object {
        const val MAX_VALUE: Long = 0xFFFF_FFFF_FFFFL

        fun parse(text: String): NodeId {
            val hex = text.replace("-", "").trim()
            require(hex.length == 12 && hex.all { it.isHex() }) {
                "NodeId canonical form is three 4-hex groups, got '$text'"
            }
            return NodeId(hex.toLong(16))
        }

        fun random(rng: Random = Random.Default): NodeId =
            NodeId(rng.nextLong(0L, MAX_VALUE + 1))

        private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
