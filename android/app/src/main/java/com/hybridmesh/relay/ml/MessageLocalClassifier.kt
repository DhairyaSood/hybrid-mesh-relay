package com.hybridmesh.relay.ml

import android.content.Context

/**
 * Offline advisory classification. Does not affect delivery.
 * Construction parses ~3.6 MB of JSON: never build this on the main thread;
 * use [MessageClassifier] via [MessageClassifierProvider].
 */
class MessageLocalClassifier internal constructor(
    private val disasterType: TfidfLogRegModel,
    private val humanitarian: TfidfLogRegModel,
    private val misuseRaw: TfidfLogRegModel,
) {
    constructor(context: Context) : this(
        TfidfLogRegModel.fromAsset(context, DISASTER_TYPE_ASSET, NeyraTextPreprocessor::cleanDisaster),
        TfidfLogRegModel.fromAsset(context, HUMANITARIAN_ASSET, NeyraTextPreprocessor::cleanDisaster),
        TfidfLogRegModel.fromAsset(context, MISUSE_ASSET, NeyraTextPreprocessor::cleanMisuse),
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
            disclaimer = DISCLAIMER,
        )
    }

    companion object {
        const val DISASTER_TYPE_ASSET = "ml/dc_t2_disaster_type.json"
        const val HUMANITARIAN_ASSET = "ml/dc_t3_humanitarian_help.json"
        const val MISUSE_ASSET = "ml/mu_v1_misuse.json"
        const val DISCLAIMER = "Advisory only — not proof of crime or verified disasters."

        /** Builds the classifier from asset JSON supplied by [readAsset] (used by JVM tests). */
        fun fromJson(readAsset: (String) -> String): MessageLocalClassifier = MessageLocalClassifier(
            TfidfLogRegModel.fromJson(readAsset(DISASTER_TYPE_ASSET), NeyraTextPreprocessor::cleanDisaster),
            TfidfLogRegModel.fromJson(readAsset(HUMANITARIAN_ASSET), NeyraTextPreprocessor::cleanDisaster),
            TfidfLogRegModel.fromJson(readAsset(MISUSE_ASSET), NeyraTextPreprocessor::cleanMisuse),
        )
    }
}
