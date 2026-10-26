package com.hybridmesh.relay.ml

import android.content.Context

/**
 * Offline advisory classification for message detail UI. Does not affect delivery.
 */
class MessageLocalClassifier(context: Context) {
    private val disasterType = TfidfLogRegModel.fromAsset(
        context,
        "ml/dc_t2_disaster_type.json",
        NeyraTextPreprocessor::cleanDisaster,
    )
    private val humanitarian = TfidfLogRegModel.fromAsset(
        context,
        "ml/dc_t3_humanitarian_help.json",
        NeyraTextPreprocessor::cleanDisaster,
    )
    private val misuseRaw = TfidfLogRegModel.fromAsset(
        context,
        "ml/mu_v1_misuse.json",
        NeyraTextPreprocessor::cleanMisuse,
    )

    data class AdvisoryResult(
        val disasterTypeDcT2: String,
        val disasterKeywordHint: String,
        val humanitarianTopic: String,
        val misuseLabel: String,
        val misuseRawLabel: String,
        val disclaimer: String,
    )

    fun classify(text: String): AdvisoryResult {
        val kw = HazardKeywordDetector.detect(text)
        val dt = disasterType.predict(text).label
        val ht = humanitarian.predict(text).label
        val rawMu = misuseRaw.predict(text).label
        val mu = MisuseCivicRefinement.refine(text, rawMu)
        return AdvisoryResult(
            disasterTypeDcT2 = dt,
            disasterKeywordHint = kw.primaryHint,
            humanitarianTopic = ht,
            misuseLabel = mu,
            misuseRawLabel = rawMu,
            disclaimer = "Advisory only — not proof of crime or verified disasters.",
        )
    }
}
