// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

/** Small strict JSON transport reader; Core is exported structurally, never parsed from dumps. */
object Json {
    fun parse(text: String): Any? = Reader(text).readDocument()
    fun stringify(value: Any?): String = buildString { appendJson(value) }

    /** Preserve a complete module document without materializing its transport tree twice. */
    internal fun appendObjectDocument(destination: StringBuilder, text: String) {
        Reader(text, materializeValues = false).readDocument(objectOnly = true)
        // A small Unicode string near the end must not widen a huge ASCII Core
        // bundle to UTF-16. Escape only string contents, preserving numeric syntax.
        var quoted = false
        var escaped = false
        var start = 0
        for (index in text.indices) {
            val c = text[index]
            if (quoted && c.code > 127) {
                destination.append(text, start, index)
                destination.append("\\u")
                for (shift in 12 downTo 0 step 4) destination.append("0123456789abcdef"[(c.code ushr shift) and 15])
                start = index + 1
            }
            if (escaped) escaped = false
            else if (quoted && c == '\\') escaped = true
            else if (c == '"') quoted = !quoted
        }
        destination.append(text, start, text.length)
    }

    // Write each value into the document buffer. Returning strings recursively
    // copies complete Core subtrees once for every enclosing object and array.
    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> {
                append('"')
                for (c in value) when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 32) {
                        append("\\u00")
                        append("0123456789abcdef"[c.code ushr 4])
                        append("0123456789abcdef"[c.code and 15])
                    } else append(c)
                }
                append('"')
            }
            is Boolean, is Number -> append(value.toString())
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((key, field) in value) {
                    require(key is String) { "JSON object key must be a string" }
                    if (!first) append(',')
                    first = false
                    appendJson(key)
                    append(':')
                    appendJson(field)
                }
                append('}')
            }
            is Iterable<*> -> appendArray(value.iterator())
            is Array<*> -> appendArray(value.iterator())
            else -> error("Unsupported JSON value: ${value.javaClass.name}")
        }
    }

    private fun StringBuilder.appendArray(values: Iterator<*>) {
        append('[')
        var first = true
        while (values.hasNext()) {
            if (!first) append(',')
            first = false
            appendJson(values.next())
        }
        append(']')
    }

    private class Reader(val text: String, private val materializeValues: Boolean = true) {
        var at = 0
        fun readDocument(objectOnly: Boolean = false): Any? {
            whitespace()
            require(!objectOnly || text.getOrNull(at) == '{') { "Expected JSON object document" }
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
        fun objectValue(): Map<String, Any?>? {
            need('{')
            val result = if (materializeValues) linkedMapOf<String, Any?>() else null
            val keys = if (materializeValues) null else hashSetOf<String>()
            if (take('}')) return result
            do {
                whitespace()
                // Validation must decode keys too: "x" and "\u0078" are duplicates.
                val key = string(materialize = true)!!
                require(if (result != null) !result.containsKey(key) else keys!!.add(key)) { "Duplicate JSON key: $key" }
                need(':')
                val field = value()
                if (result != null) result[key] = field
                if (take('}')) return result
                need(',')
            } while (true)
        }
        fun arrayValue(): List<Any?>? {
            need('[')
            val result = if (materializeValues) mutableListOf<Any?>() else null
            if (take(']')) return result
            do {
                val element = value()
                result?.add(element)
                if (take(']')) return result
                need(',')
            } while (true)
        }
        fun string(materialize: Boolean = materializeValues): String? {
            need('"')
            val result = if (materialize) StringBuilder() else null
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') return result?.toString()
                require(c.code >= 32) { "Control character in JSON string" }
                if (c != '\\') { result?.append(c); continue }
                require(at < text.length) { "Incomplete JSON escape" }
                when (val e = text[at++]) {
                    '"', '\\', '/' -> result?.append(e)
                    'b' -> result?.append('\b')
                    'f' -> result?.append('\u000c')
                    'n' -> result?.append('\n')
                    'r' -> result?.append('\r')
                    't' -> result?.append('\t')
                    'u' -> {
                        require(at + 4 <= text.length) { "Incomplete unicode escape" }
                        val decoded = text.substring(at, at + 4).toInt(16).toChar()
                        result?.append(decoded)
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
