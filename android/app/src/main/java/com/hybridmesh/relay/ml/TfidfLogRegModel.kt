package com.hybridmesh.relay.ml

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Offline TF-IDF + logistic regression from `neyra_tfidf_logreg_v2` JSON exports.
 *
 * Reproduces scikit-learn's TfidfVectorizer(token_pattern=(?u)\b\w\w+\b, sublinear_tf,
 * norm="l2") followed by LogisticRegression.decision_function, so labels and scores
 * match the Python pipeline (see TfidfLogRegModelParityTest).
 */
class TfidfLogRegModel private constructor(
    private val classOrder: List<String>,
    private val intercepts: Map<String, Double>,
    /** idf * coef per class; tokens with zero weight may be absent. */
    private val weights: Map<String, Map<String, Double>>,
    /** idf for every vocabulary term; defines which n-grams count toward the L2 norm. */
    private val idf: Map<String, Double>,
    private val ngramMin: Int,
    private val ngramMax: Int,
    private val sublinearTf: Boolean,
    private val l2Normalize: Boolean,
    private val preprocess: (String) -> String,
) {
    data class Prediction(
        val label: String,
        val scores: Map<String, Double>,
    )

    fun predict(text: String): Prediction {
        val counts = HashMap<String, Int>()
        for (gram in buildNgrams(tokenize(preprocess(text)), ngramMin, ngramMax)) {
            if (gram in idf) counts[gram] = (counts[gram] ?: 0) + 1
        }

        val termFrequency = counts.mapValues { (_, count) ->
            if (sublinearTf) 1.0 + ln(count.toDouble()) else count.toDouble()
        }
        val norm = if (l2Normalize) {
            sqrt(termFrequency.entries.sumOf { (term, tf) -> (tf * idf.getValue(term)).let { it * it } })
        } else {
            1.0
        }
        val scale = if (norm > 0.0) 1.0 / norm else 0.0

        val scores = LinkedHashMap<String, Double>()
        for (label in classOrder) {
            val classWeights = weights[label].orEmpty()
            var linear = 0.0
            for ((term, tf) in termFrequency) {
                linear += tf * (classWeights[term] ?: continue)
            }
            scores[label] = (intercepts[label] ?: 0.0) + linear * scale
        }
        // First maximum in class order, like numpy argmax.
        val best = classOrder.maxByOrNull { scores.getValue(it) } ?: classOrder.first()
        return Prediction(best, scores.toList().sortedByDescending { it.second }.toMap())
    }

    companion object {
        private const val SUPPORTED_FORMAT = "neyra_tfidf_logreg_v2"
        private const val SKLEARN_DEFAULT_TOKEN_PATTERN = "(?u)\\b\\w\\w+\\b"

        fun fromAsset(context: Context, assetPath: String, preprocess: (String) -> String): TfidfLogRegModel {
            val json = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            return fromJson(json, preprocess)
        }

        fun fromJson(json: String, preprocess: (String) -> String): TfidfLogRegModel {
            val root = JSONObject(json)
            val format = root.optString("format")
            require(format == SUPPORTED_FORMAT) {
                "Unsupported model format '$format'; re-export with idf_by_token ($SUPPORTED_FORMAT)"
            }
            val vectorizer = root.getJSONObject("vectorizer")
            val tokenPattern = vectorizer.getString("token_pattern")
            require(tokenPattern == SKLEARN_DEFAULT_TOKEN_PATTERN) { "Unsupported token_pattern $tokenPattern" }
            val norm = if (vectorizer.isNull("norm")) null else vectorizer.getString("norm")
            require(norm == null || norm == "l2") { "Unsupported norm $norm" }

            val classOrder = root.getJSONArray("class_order").let { arr ->
                List(arr.length()) { i -> arr.getString(i) }
            }
            val interceptJson = root.getJSONObject("intercepts")
            val intercepts = interceptJson.keys().asSequence().associateWith { interceptJson.getDouble(it) }
            val weightJson = root.getJSONObject("token_weights_by_class")
            val weights = weightJson.keys().asSequence().associateWith { label ->
                val inner = weightJson.getJSONObject(label)
                HashMap<String, Double>(inner.length() * 2).apply {
                    inner.keys().forEach { token -> put(token, inner.getDouble(token)) }
                }
            }
            val idfJson = root.getJSONObject("idf_by_token")
            val idf = HashMap<String, Double>(idfJson.length() * 2).apply {
                idfJson.keys().forEach { token -> put(token, idfJson.getDouble(token)) }
            }
            val ngram = root.getJSONArray("ngram_range")
            return TfidfLogRegModel(
                classOrder = classOrder,
                intercepts = intercepts,
                weights = weights,
                idf = idf,
                ngramMin = ngram.getInt(0),
                ngramMax = ngram.getInt(1),
                sublinearTf = vectorizer.getBoolean("sublinear_tf"),
                l2Normalize = norm == "l2",
                preprocess = preprocess,
            )
        }

        /**
         * sklearn's default token_pattern `(?u)\b\w\w+\b`: maximal runs of Python
         * word characters, keeping runs of two or more code points. Python's `\w`
         * is a letter, a number or `_`; combining marks (e.g. Devanagari vowel
         * signs) are not word characters and split tokens, exactly as in training.
         */
        internal fun tokenize(text: String): List<String> {
            val tokens = ArrayList<String>()
            var start = -1
            var codePoints = 0
            var i = 0
            while (i <= text.length) {
                val cp = if (i < text.length) text.codePointAt(i) else -1
                if (cp >= 0 && isPythonWordChar(cp)) {
                    if (start < 0) {
                        start = i
                        codePoints = 0
                    }
                    codePoints++
                } else if (start >= 0) {
                    if (codePoints >= 2) tokens += text.substring(start, i)
                    start = -1
                }
                i += if (cp >= 0) Character.charCount(cp) else 1
            }
            return tokens
        }

        internal fun isPythonWordChar(cp: Int): Boolean {
            if (cp == '_'.code || Character.isLetter(cp)) return true
            return when (Character.getType(cp).toByte()) {
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
                else -> false
            }
        }

        private fun buildNgrams(tokens: List<String>, minN: Int, maxN: Int): List<String> {
            if (tokens.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            for (n in minN..maxN) {
                if (tokens.size < n) continue
                for (i in 0..tokens.size - n) {
                    out += tokens.subList(i, i + n).joinToString(" ")
                }
            }
            return out
        }
    }
}
