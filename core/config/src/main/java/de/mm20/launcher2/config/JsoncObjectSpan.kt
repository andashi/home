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
 * and leave every other byte, comments included, as it was.
 */
object JsoncObjectSpan {
    fun find(text: String, path: List<String>): JsoncSpanResult = TODO()
}
