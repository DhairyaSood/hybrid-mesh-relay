package com.hybridmesh.relay.ml

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The shipped JSON models must reproduce scikit-learn exactly. Expected labels and
 * decision scores come from experiments/mobile_parity/export_parity_fixture.py,
 * which runs the original joblib pipelines on the same texts.
 */
class TfidfLogRegModelParityTest {
    private val fixture = JSONObject(readResource("ml/parity_fixture.json"))
    private val texts = fixture.getJSONArray("texts").let { arr -> List(arr.length()) { arr.getString(it) } }

    private fun readResource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)) { "missing test resource $path" }.readText()

    // Gradle runs unit tests with the module directory (android/app) as working directory.
    private fun loadAsset(name: String, preprocess: (String) -> String): TfidfLogRegModel =
        TfidfLogRegModel.fromJson(File("src/main/assets/ml/$name.json").readText(), preprocess)

    private fun assertParity(name: String, preprocess: (String) -> String) {
        val model = loadAsset(name, preprocess)
        val expected = fixture.getJSONObject("expected").getJSONArray(name)
        val labelMismatches = mutableListOf<String>()
        var worstScoreError = 0.0
        texts.forEachIndexed { i, text ->
            val want = expected.getJSONObject(i)
            val got = model.predict(text)
            if (got.label != want.getString("label")) {
                labelMismatches += "#$i '${text.take(60)}': got ${got.label}, sklearn ${want.getString("label")}"
            }
            val wantScores = want.getJSONObject("scores")
            for (label in wantScores.keys()) {
                worstScoreError = maxOf(worstScoreError, abs((got.scores[label] ?: Double.NaN) - wantScores.getDouble(label)))
            }
        }
        assertEquals(
            "$name: ${labelMismatches.size}/${texts.size} labels differ from sklearn:\n" + labelMismatches.take(10).joinToString("\n"),
            0,
            labelMismatches.size
        )
        assertTrue("$name: decision scores differ from sklearn by up to $worstScoreError", worstScoreError < 1e-6)
    }

    @Test
    fun disasterTypeMatchesSklearn() = assertParity("dc_t2_disaster_type", NeyraTextPreprocessor::cleanDisaster)

    @Test
    fun humanitarianHelpMatchesSklearn() = assertParity("dc_t3_humanitarian_help", NeyraTextPreprocessor::cleanDisaster)

    @Test
    fun misuseMatchesSklearn() = assertParity("mu_v1_misuse", NeyraTextPreprocessor::cleanMisuse)

    @Test
    fun emptyTextScoresInterceptsOnly() {
        val model = loadAsset("dc_t2_disaster_type", NeyraTextPreprocessor::cleanDisaster)
        val prediction = model.predict("   ")
        assertTrue(prediction.label.isNotBlank())
    }
}
