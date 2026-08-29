package org.groundzero.mesh.propagation

import org.groundzero.mesh.agent.SlmFeatureVector

/**
 * The phone-to-phone projection: plain JSON, readable in a log, forgiving to evolve.
 *
 * `core` carries no third-party runtime dependency, so the tiny reader/writer below is
 * hand-rolled and covers exactly the shapes an [Envelope] needs — flat object, string
 * enums, one nullable string, two arrays of primitives.
 */
object JsonCodec : EnvelopeCodec {

    override val name: String = "json"

    override fun encode(envelope: Envelope): ByteArray = buildString {
        append('{')
        field("nodeId", envelope.nodeId.canonical()); append(',')
        field("saltFingerprint", envelope.saltFingerprint); append(',')
        field("addressZone", envelope.addressZone); append(',')
        field("tier", envelope.tier.name); append(',')
        field("severity", envelope.severity.name); append(',')
        rawField("dangerScore", envelope.dangerScore.toString()); append(',')
        rawField("timestamp", envelope.timestamp.toString()); append(',')
        if (envelope.slmSummary == null) rawField("slmSummary", "null")
        else field("slmSummary", envelope.slmSummary)
        append(',')
        rawField("flags", (envelope.flags.toInt() and 0xFF).toString()); append(',')
        rawField(
            "featureVector",
            envelope.featureVector?.toList()?.joinToString(",", "[", "]") { it.toString() }
                ?: "null",
        )
        append(',')
        rawField("views", envelope.views.joinToString(",", "[", "]") { quote(it) }); append(',')
        rawField("peers", envelope.peers.joinToString(",", "[", "]") { quote(it.canonical()) }); append(',')
        rawField("hops", envelope.hops.toString()); append(',')
        rawField("ttl", envelope.ttl.toString()); append(',')
        rawField("gpsLat", envelope.gpsLat?.toString() ?: "null"); append(',')
        rawField("gpsLon", envelope.gpsLon?.toString() ?: "null")
        append('}')
    }.toByteArray(Charsets.UTF_8)

    override fun decode(bytes: ByteArray): Envelope {
        try {
            val obj = JsonParser(String(bytes, Charsets.UTF_8)).parseObject()
            return Envelope(
                nodeId = NodeId.parse(obj.str("nodeId")),
                saltFingerprint = obj.str("saltFingerprint"),
                addressZone = obj.str("addressZone"),
                tier = EpistemologyTier.valueOf(obj.str("tier")),
                severity = Severity.valueOf(obj.str("severity")),
                dangerScore = obj.num("dangerScore"),
                timestamp = obj.num("timestamp").toLong(),
                slmSummary = obj.strOrNull("slmSummary"),
                flags = obj.num("flags").toInt().toByte(),
                featureVector = obj.floatArrayOrNull("featureVector")
                    ?.let { SlmFeatureVector(it) },
                views = obj.strArray("views"),
                peers = obj.strArray("peers").map { NodeId.parse(it) },
                hops = obj.num("hops").toInt(),
                ttl = obj.num("ttl").toInt(),
                gpsLat = obj.numOrNull("gpsLat")?.toFloat(),
                gpsLon = obj.numOrNull("gpsLon")?.toFloat(),
            )
        } catch (e: Exception) {
            throw EnvelopeDecodeException("json decode failed: ${e.message}", e)
        }
    }

    private fun StringBuilder.field(key: String, value: String) {
        append(quote(key)); append(':'); append(quote(value))
    }

    private fun StringBuilder.rawField(key: String, rawValue: String) {
        append(quote(key)); append(':'); append(rawValue)
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}

// --- minimal JSON reader, private to this file ---

private class JsonObj(val map: Map<String, Any?>) {
    fun str(key: String): String = map[key] as? String ?: error("expected string field '$key'")
    fun strOrNull(key: String): String? = map[key] as? String
    fun num(key: String): Double = when (val v = map[key]) {
        is Double -> v
        is Long -> v.toDouble()
        else -> error("expected number field '$key'")
    }

    /** Null both when the key is JSON `null` and when it is absent entirely (older payload). */
    fun numOrNull(key: String): Double? = when (val v = map[key]) {
        is Double -> v
        is Long -> v.toDouble()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun strArray(key: String): List<String> =
        (map[key] as? List<Any?> ?: error("expected array field '$key'")).map { it as String }

    fun floatArrayOrNull(key: String): FloatArray? {
        val list = map[key] as? List<*> ?: return null
        return FloatArray(list.size) { i ->
            when (val v = list[i]) {
                is Double -> v.toFloat()
                is Long -> v.toFloat()
                else -> error("expected number in '$key'")
            }
        }
    }
}

private class JsonParser(private val s: String) {
    private var i = 0

    fun parseObject(): JsonObj {
        val v = parseValue()
        @Suppress("UNCHECKED_CAST")
        return JsonObj(v as? Map<String, Any?> ?: error("top level is not an object"))
    }

    private fun parseValue(): Any? {
        skipWs()
        val c = s[i]
        return when {
            c == '{' -> parseObj()
            c == '[' -> parseArr()
            c == '"' -> parseStr()
            c == 't' -> literal("true", true)
            c == 'f' -> literal("false", false)
            c == 'n' -> literal("null", null)
            c == '-' || c in '0'..'9' -> parseNum()
            else -> error("unexpected '$c' at $i")
        }
    }

    private fun parseObj(): Map<String, Any?> {
        expect('{'); skipWs()
        val m = LinkedHashMap<String, Any?>()
        if (s[i] == '}') { i++; return m }
        while (true) {
            skipWs()
            val key = parseStr()
            skipWs(); expect(':')
            m[key] = parseValue()
            skipWs()
            when (val c = s[i++]) {
                ',' -> continue
                '}' -> return m
                else -> error("expected ',' or '}' got '$c' at $i")
            }
        }
    }

    private fun parseArr(): List<Any?> {
        expect('['); skipWs()
        val list = ArrayList<Any?>()
        if (s[i] == ']') { i++; return list }
        while (true) {
            list.add(parseValue())
            skipWs()
            when (val c = s[i++]) {
                ',' -> continue
                ']' -> return list
                else -> error("expected ',' or ']' got '$c' at $i")
            }
        }
    }

    private fun parseStr(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    val e = s[i++]
                    when (e) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            val hex = s.substring(i, i + 4); i += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> error("bad escape '\\$e' at $i")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNum(): Any {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                s[i] == '+' || s[i] == '-')) i++
        val text = s.substring(start, i)
        return if (text.any { it == '.' || it == 'e' || it == 'E' }) text.toDouble() else text.toLong()
    }

    private fun <T> literal(word: String, value: T): T {
        require(s.regionMatches(i, word, 0, word.length)) { "expected '$word' at $i" }
        i += word.length
        return value
    }

    private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun expect(c: Char) {
        val got = s[i++]
        require(got == c) { "expected '$c' got '$got' at $i" }
    }
}
