package org.groundzero.mesh.llm

/**
 * A minimal JSON reader/writer for the advisor.
 *
 * `core` carries no third-party runtime dependency (see `core/build.gradle.kts`), and the
 * one hand-rolled parser that already exists — inside `JsonCodec.kt` — is `private` to that
 * file and shaped for a flat [org.groundzero.mesh.propagation.Envelope]: string enums, one
 * nullable string, two arrays of primitives. Widening a wire codec's surface so an advisory
 * feature can borrow it would make the codec responsible for shapes it never encodes.
 *
 * What this one has to read is a different shape entirely: the gateway's `/snapshot` payload
 * (nested objects, an array of objects, nulls in numeric slots) and Ollama's chat response
 * (nested `message.content`). So it is a general reader, kept small, `internal`, and tested
 * on its own.
 */
internal object Json {

    /** Parses a document into `Map<String, Any?>` / `List<Any?>` / `String` / `Double` / `Long` / `Boolean` / null. */
    fun parse(text: String): Any? = Reader(text).parseDocument()

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun asList(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    fun str(obj: Map<String, Any?>?, key: String): String? = obj?.get(key) as? String

    fun num(obj: Map<String, Any?>?, key: String): Double? = when (val v = obj?.get(key)) {
        is Double -> v
        is Long -> v.toDouble()
        else -> null
    }

    fun int(obj: Map<String, Any?>?, key: String): Int? = num(obj, key)?.toInt()

    fun bool(obj: Map<String, Any?>?, key: String): Boolean? = obj?.get(key) as? Boolean

    /** Every element that is a string; anything else in the array is skipped, not faked. */
    fun strList(obj: Map<String, Any?>?, key: String): List<String> =
        asList(obj?.get(key)).mapNotNull { it as? String }

    fun quote(s: String): String = buildString {
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

    /** `"key":<raw>` — the caller supplies already-encoded JSON for the value. */
    fun raw(key: String, value: String): String = quote(key) + ":" + value

    fun field(key: String, value: String): String = quote(key) + ":" + quote(value)

    fun array(values: List<String>): String = values.joinToString(",", "[", "]") { quote(it) }

    private class Reader(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            val v = parseValue()
            skipWs()
            return v
        }

        fun parseValue(): Any? {
            skipWs()
            require(i < s.length) { "unexpected end of input" }
            return when (val c = s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') parseNum() else error("unexpected '$c' at $i")
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
                    '\\' -> when (val e = s[i++]) {
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
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNum(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    s[i] == '+' || s[i] == '-')
            ) i++
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
}
