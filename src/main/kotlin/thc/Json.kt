package thc

/** Small strict JSON transport reader; Core is exported structurally, never parsed from dumps. */
object Json {
    fun parse(text: String): Any? = Reader(text).readDocument()
    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            require(it.key is String) { "JSON object key must be a string" }
            stringify(it.key) + ":" + stringify(it.value)
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        else -> error("Unsupported JSON value: ${value.javaClass.name}")
    }

    private class Reader(val text: String) {
        var at = 0
        fun readDocument(): Any? {
            val value = value()
            whitespace()
            require(at == text.length) { "Trailing JSON at $at" }
            return value
        }
        fun whitespace() { while (at < text.length && text[at] in " \n\r\t") at++ }
        fun value(): Any? {
            whitespace()
            require(at < text.length) { "Unexpected end of JSON" }
            return when (text[at]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> string()
                't' -> keyword("true", true)
                'f' -> keyword("false", false)
                'n' -> keyword("null", null)
                '-', in '0'..'9' -> number()
                else -> error("Unexpected JSON character at $at")
            }
        }
        fun keyword(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "Expected $word at $at" }
            at += word.length
            return value
        }
        fun take(c: Char): Boolean {
            whitespace()
            if (at < text.length && text[at] == c) { at++; return true }
            return false
        }
        fun need(c: Char) { require(take(c)) { "Expected $c at $at" } }
        fun objectValue(): Map<String, Any?> {
            need('{')
            val result = linkedMapOf<String, Any?>()
            if (take('}')) return result
            do {
                whitespace()
                val key = string()
                require(!result.containsKey(key)) { "Duplicate JSON key: $key" }
                need(':')
                result[key] = value()
                if (take('}')) return result
                need(',')
            } while (true)
        }
        fun arrayValue(): List<Any?> {
            need('[')
            val result = mutableListOf<Any?>()
            if (take(']')) return result
            do {
                result.add(value())
                if (take(']')) return result
                need(',')
            } while (true)
        }
        fun string(): String {
            need('"')
            val result = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') return result.toString()
                require(c.code >= 32) { "Control character in JSON string" }
                if (c != '\\') { result.append(c); continue }
                require(at < text.length) { "Incomplete JSON escape" }
                when (val e = text[at++]) {
                    '"', '\\', '/' -> result.append(e)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        require(at + 4 <= text.length) { "Incomplete unicode escape" }
                        result.append(text.substring(at, at + 4).toInt(16).toChar())
                        at += 4
                    }
                    else -> error("Unknown JSON escape: $e")
                }
            }
            error("Unterminated JSON string")
        }
        fun number(): Number {
            val start = at
            if (text[at] == '-') at++
            require(at < text.length && text[at].isDigit()) { "Invalid JSON number" }
            if (text[at] == '0') at++ else while (at < text.length && text[at].isDigit()) at++
            var floating = false
            if (at < text.length && text[at] == '.') {
                floating = true; at++
                require(at < text.length && text[at].isDigit()) { "Invalid fraction" }
                while (at < text.length && text[at].isDigit()) at++
            }
            if (at < text.length && text[at] in "eE") {
                floating = true; at++
                if (at < text.length && text[at] in "+-") at++
                require(at < text.length && text[at].isDigit()) { "Invalid exponent" }
                while (at < text.length && text[at].isDigit()) at++
            }
            val value = text.substring(start, at)
            return if (floating) value.toDouble().also { require(it.isFinite()) } else value.toLong()
        }
    }
}
