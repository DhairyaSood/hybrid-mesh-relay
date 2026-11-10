package com.hybridmesh.relay.ml

object NeyraTextPreprocessor {
    private val url = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val mention = Regex("""@\w+""")
    private val ws = Regex("""\s+""")

    fun cleanDisaster(text: String): String {
        var out = text
        out = url.replace(out, " ")
        out = mention.replace(out, " ")
        out = out.lowercase()
        return ws.replace(out, " ").trim()
    }

    fun cleanMisuse(text: String): String = cleanDisaster(text)
}
