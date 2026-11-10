package com.hybridmesh.relay.ml

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln

/**
 * Offline TF-IDF + logistic regression from [neyra_tfidf_logreg_v1] JSON exports.
 */
class TfidfLogRegModel private constructor(
    private val classOrder: List<String>,
    private val intercepts: Map<String, Double>,
    private val weights: Map<String, Map<String, Double>>,
    private val ngramMin: Int,
    private val ngramMax: Int,
    private val sublinearTf: Boolean,
    private val preprocess: (String) -> String,
) {
    data class Prediction(
        val label: String,
        val scores: Map<String, Double>,
    )

    fun predict(text: String): Prediction {
        val cleaned = preprocess(text)
        val tokens = if (cleaned.isBlank()) emptyList() else cleaned.split(' ')
        val ngrams = buildNgrams(tokens, ngramMin, ngramMax)
        val tf = mutableMapOf<String, Int>()
        for (g in ngrams) tf[g] = (tf[g] ?: 0) + 1

        val scores = mutableMapOf<String, Double>()
        for (label in classOrder) {
            var s = intercepts[label] ?: 0.0
            val w = weights[label] ?: emptyMap()
            for ((term, count) in tf) {
                val weight = w[term] ?: continue
                val t = if (sublinearTf) 1.0 + ln(count.toDouble()) else count.toDouble()
                s += t * weight
            }
            scores[label] = s
        }
        val best = scores.maxByOrNull { it.value }?.key ?: classOrder.first()
        return Prediction(best, scores.toList().sortedByDescending { it.second }.toMap())
    }

    companion object {
        fun fromAsset(context: Context, assetPath: String, preprocess: (String) -> String): TfidfLogRegModel {
            val json = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val classOrder = root.getJSONArray("class_order").let { arr ->
                List(arr.length()) { i -> arr.getString(i) }
            }
            val intercepts = mutableMapOf<String, Double>()
            root.getJSONObject("intercepts").keys().forEach { k ->
                intercepts[k] = root.getJSONObject("intercepts").getDouble(k)
            }
            val weights = mutableMapOf<String, Map<String, Double>>()
            val tw = root.getJSONObject("token_weights_by_class")
            for (label in tw.keys()) {
                val inner = tw.getJSONObject(label)
                val m = mutableMapOf<String, Double>()
                inner.keys().forEach { tok -> m[tok] = inner.getDouble(tok) }
                weights[label] = m
            }
            val ngram = root.getJSONArray("ngram_range")
            val pre = root.optJSONObject("preprocess")
            val sublinear = pre?.optBoolean("sublinear_tf", true) ?: true
            return TfidfLogRegModel(
                classOrder,
                intercepts,
                weights,
                ngram.getInt(0),
                ngram.getInt(1),
                sublinear,
                preprocess,
            )
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
