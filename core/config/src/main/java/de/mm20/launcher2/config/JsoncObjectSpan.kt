package de.mm20.launcher2.config

/** Where a key's value sits in a JSONC document, or where it would go. See [JsoncObjectSpan]. */
sealed class JsoncSpanResult {
    /** The value of the last key in the path spans `text[start, endExclusive)`. */
    data class Found(val start: Int, val endExclusive: Int) : JsoncSpanResult()

    /**
     * The last key is absent, every parent exists. [at] is the position right
     * after the parent's last member (or its trailing comma, or the opening
     * brace when the parent is empty), which is where the new member goes so
     * that the closing brace and whatever precedes it stay as they were.
     * [needsComma] says whether the member has to be introduced by a comma,
     * [indent] is the indentation the parent's members use, [closingIndent]
     * that of the line holding the parent's opening brace.
     */
    data class Insert(
        val at: Int,
        val needsComma: Boolean,
        val indent: String,
        val closingIndent: String,
    ) : JsoncSpanResult()

    /** The parent object at `path[depth]` does not exist (or is not an object). */
    data class MissingParent(val depth: Int) : JsoncSpanResult()

    /** The text is not a JSONC object the scanner can walk. */
    data object Malformed : JsoncSpanResult()
}

/**
 * Finds the text span of one object member in a JSONC document without
 * parsing it into a tree, so that a caller can replace exactly that span
 * and leave every other byte, comments included, as it was (ADR 0003,
 * write-back).
 *
 * One pass over the text: strings (with `\` escapes), `//` and `/* */`
 * comments and bracket nesting are tracked, values are not interpreted
 * beyond their extent. Keys match only at the depth the path names. When a
 * key appears twice at that depth the first one wins: JSON leaves the case
 * undefined, and write-back has to pick one span to replace rather than add
 * a third copy.
 */
object JsoncObjectSpan {

    fun find(text: String, path: List<String>): JsoncSpanResult {
        require(path.isNotEmpty()) { "path must name at least one key" }
        val scanner = Scanner(text)
        return try {
            val start = scanner.skipWs(0)
            if (start >= text.length || text[start] != '{') return JsoncSpanResult.Malformed
            scanner.findIn(start, path, 0)
        } catch (e: MalformedInput) {
            JsoncSpanResult.Malformed
        }
    }

    /** The leading whitespace of the line that contains [pos]. */
    fun lineIndent(text: String, pos: Int): String {
        val lineStart = text.lastIndexOf('\n', pos - 1) + 1
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.substring(lineStart, i)
    }

    private class MalformedInput : RuntimeException()

    private class Scanner(private val t: String) {
        private val len = t.length

        fun findIn(openBrace: Int, path: List<String>, depth: Int): JsoncSpanResult {
            var i = skipWs(openBrace + 1)
            var lastMemberEnd = openBrace + 1
            var lastMemberIndent: String? = null
            var hasMembers = false
            var trailingComma = false
            while (true) {
                if (i >= len) throw MalformedInput()
                if (t[i] == '}') {
                    if (depth != path.lastIndex) return JsoncSpanResult.MissingParent(depth)
                    val closingIndent = lineIndent(t, openBrace)
                    return JsoncSpanResult.Insert(
                        at = lastMemberEnd,
                        needsComma = hasMembers && !trailingComma,
                        indent = lastMemberIndent ?: (closingIndent + "  "),
                        closingIndent = closingIndent,
                    )
                }
                if (t[i] != '"') throw MalformedInput()
                val keyStart = i
                val keyEnd = skipString(i)
                val key = unescape(t.substring(keyStart + 1, keyEnd - 1))
                i = skipWs(keyEnd)
                if (i >= len || t[i] != ':') throw MalformedInput()
                i = skipWs(i + 1)
                if (i >= len) throw MalformedInput()
                val valueStart = i
                val valueEnd = skipValue(i)
                if (key == path[depth]) {
                    if (depth == path.lastIndex) return JsoncSpanResult.Found(valueStart, valueEnd)
                    if (t[valueStart] != '{') return JsoncSpanResult.MissingParent(depth)
                    return findIn(valueStart, path, depth + 1)
                }
                hasMembers = true
                lastMemberIndent = lineIndent(t, keyStart)
                lastMemberEnd = valueEnd
                trailingComma = false
                i = skipWs(valueEnd)
                if (i >= len) throw MalformedInput()
                when (t[i]) {
                    ',' -> {
                        trailingComma = true
                        lastMemberEnd = i + 1
                        i = skipWs(i + 1)
                    }
                    '}' -> Unit
                    else -> throw MalformedInput()
                }
            }
        }

        /** Skips whitespace and comments; an unterminated block comment is malformed. */
        fun skipWs(from: Int): Int {
            var i = from
            while (i < len) {
                val c = t[i]
                when {
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                    c == '/' && i + 1 < len && t[i + 1] == '/' -> {
                        val eol = t.indexOf('\n', i)
                        i = if (eol < 0) len else eol + 1
                    }
                    c == '/' && i + 1 < len && t[i + 1] == '*' -> {
                        val end = t.indexOf("*/", i + 2)
                        if (end < 0) throw MalformedInput()
                        i = end + 2
                    }
                    else -> return i
                }
            }
            return i
        }

        /** [from] is at the opening quote; returns the index after the closing one. */
        fun skipString(from: Int): Int {
            var i = from + 1
            while (i < len) {
                when (t[i]) {
                    '\\' -> i += 2
                    '"' -> return i + 1
                    else -> i++
                }
            }
            throw MalformedInput()
        }

        /** Returns the exclusive end of the value starting at [from]. */
        fun skipValue(from: Int): Int {
            val c = t[from]
            if (c == '"') return skipString(from)
            if (c == '{' || c == '[') {
                var depth = 0
                var i = from
                while (i < len) {
                    val ch = t[i]
                    when {
                        ch == '"' -> {
                            i = skipString(i)
                            continue
                        }
                        ch == '/' && i + 1 < len && (t[i + 1] == '/' || t[i + 1] == '*') -> {
                            i = skipWs(i)
                            continue
                        }
                        ch == '{' || ch == '[' -> depth++
                        ch == '}' || ch == ']' -> {
                            depth--
                            if (depth == 0) return i + 1
                            if (depth < 0) throw MalformedInput()
                        }
                    }
                    i++
                }
                throw MalformedInput()
            }
            // A scalar: up to the next separator, whitespace or comment.
            var i = from
            while (i < len) {
                val ch = t[i]
                if (ch == ',' || ch == '}' || ch == ']' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') break
                if (ch == '/' && i + 1 < len && (t[i + 1] == '/' || t[i + 1] == '*')) break
                i++
            }
            if (i == from) throw MalformedInput()
            return i
        }

        /**
         * Decodes the escapes of a key. A `\u` without four hex digits, or a
         * backslash with nothing after it, is malformed: keys are compared
         * unescaped, and a key that cannot be decoded cannot be matched.
         */
        private fun unescape(raw: String): String {
            if ('\\' !in raw) return raw
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '\\') {
                    sb.append(c)
                    i++
                    continue
                }
                if (i + 1 >= raw.length) throw MalformedInput()
                when (val e = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('')
                    'u' -> {
                        if (i + 6 > raw.length) throw MalformedInput()
                        val code = raw.substring(i + 2, i + 6).toIntOrNull(16) ?: throw MalformedInput()
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
                i += 2
            }
            return sb.toString()
        }
    }
}
