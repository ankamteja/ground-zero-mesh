package org.groundzero.mesh.propagation

/** One wire projection of an [Envelope]. Byte-oriented; the transport never sees this type. */
interface EnvelopeCodec {
    val name: String
    fun encode(envelope: Envelope): ByteArray
    fun decode(bytes: ByteArray): Envelope
}

class EnvelopeDecodeException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Codec selection. Driven by the transport's declared frame budget, never hardcoded at a
 * call site.
 *
 * - budget below [JSON_MIN_BUDGET] (LoRa, ~233 bytes) -> [CompactCodec]
 * - budget at or above it (Nearby, Wi-Fi Direct)      -> [JsonCodec]
 */
object Codecs {
    const val JSON_MIN_BUDGET = 512

    val json: EnvelopeCodec = JsonCodec
    val compact: EnvelopeCodec = CompactCodec

    fun forFrameBudget(maxFrameBytes: Int): EnvelopeCodec =
        if (maxFrameBytes >= JSON_MIN_BUDGET) json else compact
}
