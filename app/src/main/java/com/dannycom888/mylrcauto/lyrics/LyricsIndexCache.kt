package com.dannycom888.mylrcauto.lyrics

/**
 * The lyrics file index, saved to disk as JSON so a fresh process can serve
 * the first lookup at once instead of walking the whole folder first.
 *
 * Pure Kotlin with no Android dependency, so the round-trip is directly
 * unit-testable on the JVM. The JSON is written and read by the small
 * hand-rolled codec below rather than a library: the app has no JSON
 * dependency, and the shape is fixed.
 *
 * Shape:
 * `{"v":2,"folder":"<folder uri>","lrc":[{"u":"<uri>","n":"<name>"}, ...],"audio":[...]}`
 *
 * Parsing is strict: anything malformed returns null and the caller treats
 * it as "no cache" and walks the folder instead.
 *
 * Format v2 (v1.0.23) carries the same shape as v1; the version bump is
 * the invalidation. A v1 cache was built by the old `DocumentFile` walk,
 * which could silently drop rows (see [FolderWalk]) and then froze that
 * degraded listing to disk, re-serving it across restarts — so any cache
 * written by v1.0.22 or earlier is discarded and rebuilt once by the
 * hardened walk instead of being trusted.
 */
object LyricsIndexCache {

    /** The only cache format version this build writes or accepts. */
    private const val FORMAT_VERSION = 2L

    /** One cached index, as read back from disk. */
    data class CachedIndex(
        val folderUri: String,
        val lrc: List<LocalLrcFile>,
        val audio: List<LocalAudioFile>,
    )

    fun serialize(folderUri: String, lrc: List<LocalLrcFile>, audio: List<LocalAudioFile>): String {
        val out = StringBuilder()
        out.append("{\"v\":2,\"folder\":")
        out.append(escape(folderUri))
        out.append(",\"lrc\":[")
        lrc.forEachIndexed { index, file ->
            if (index > 0) out.append(',')
            out.append(entry(file.uriString, file.fileName))
        }
        out.append("],\"audio\":[")
        audio.forEachIndexed { index, file ->
            if (index > 0) out.append(',')
            out.append(entry(file.uriString, file.fileName))
        }
        out.append("]}")
        return out.toString()
    }

    private fun entry(uriString: String, fileName: String): String =
        "{\"u\":" + escape(uriString) + ",\"n\":" + escape(fileName) + "}"

    /** Parses a cache file, or null when it is not a well-formed current-version cache. */
    fun parse(json: String): CachedIndex? {
        val root = try {
            val parser = JsonParser(json)
            val value = parser.parseValue()
            parser.requireEnd()
            value as? Map<*, *>
        } catch (_: Exception) {
            return null
        } ?: return null
        if ((root["v"] as? Number)?.toLong() != FORMAT_VERSION) return null
        val folderUri = root["folder"] as? String ?: return null
        val lrc = entries(root["lrc"]) { uri, name -> LocalLrcFile(uri, name) } ?: return null
        val audio = entries(root["audio"]) { uri, name -> LocalAudioFile(uri, name) } ?: return null
        return CachedIndex(folderUri, lrc, audio)
    }

    private fun <T> entries(value: Any?, build: (String, String) -> T): List<T>? {
        val list = value as? List<*> ?: return null
        val out = ArrayList<T>(list.size)
        for (raw in list) {
            val obj = raw as? Map<*, *> ?: return null
            val uri = obj["u"] as? String ?: return null
            val name = obj["n"] as? String ?: return null
            out += build(uri, name)
        }
        return out
    }

    /** Minimal JSON string escaping: quotes, backslashes and control characters. */
    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u%04x".format(ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    /** Tiny strict JSON reader: objects, arrays, strings, numbers, booleans, null. */
    private class JsonParser(private val input: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWs()
            return when (val ch = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> {
                    expect("true")
                    true
                }
                'f' -> {
                    expect("false")
                    false
                }
                'n' -> {
                    expect("null")
                    null
                }
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected character '$ch' at $pos")
            }
        }

        fun requireEnd() {
            skipWs()
            if (pos != input.length) throw IllegalArgumentException("Trailing content at $pos")
        }

        private fun parseObject(): Map<String, Any?> {
            expectChar('{')
            val map = linkedMapOf<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expectChar(':')
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expectChar('[')
            val list = arrayListOf<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list += parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= input.length) throw IllegalArgumentException("Unterminated string")
                val ch = input[pos++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= input.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = input[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > input.length) {
                                    throw IllegalArgumentException("Bad \\u escape")
                                }
                                val code = input.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Bad \\u escape")
                                pos += 4
                                out.append(code.toChar())
                            }
                            else -> throw IllegalArgumentException("Bad escape '\\$esc'")
                        }
                    }
                    else -> out.append(ch)
                }
            }
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.' || input[pos] == 'e' || input[pos] == 'E' || input[pos] == '+' || input[pos] == '-')) pos++
            val text = input.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull()
                ?: throw IllegalArgumentException("Bad number '$text'")
        }

        private fun expect(literal: String) {
            if (!input.startsWith(literal, pos)) {
                throw IllegalArgumentException("Expected '$literal' at $pos")
            }
            pos += literal.length
        }

        private fun expectChar(ch: Char) {
            skipWs()
            if (pos >= input.length || input[pos] != ch) {
                throw IllegalArgumentException("Expected '$ch' at $pos")
            }
            pos++
        }

        private fun skipWs() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < input.length) input[pos] else '\u0000'
    }
}
