package com.hybridmesh.relay.ml

/** Mirrors experiments/disaster_classifier/src/preprocess.py clean_text. */
object NeyraTextPreprocessor {
    private val url = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    // Python's \w in str patterns is Unicode: letters, numbers and underscore.
    private val mention = Regex("""@[\p{L}\p{N}_]+""")
    // Python's \s in str patterns is Unicode whitespace (e.g. NBSP, em space).
    private val ws = Regex("""(?U)\s+""")

    fun cleanDisaster(text: String): String {
        var out = text
        out = url.replace(out, " ")
        out = mention.replace(out, " ")
        out = out.lowercase()
        return ws.replace(out, " ").trim()
    }

    fun cleanMisuse(text: String): String = cleanDisaster(text)
}
