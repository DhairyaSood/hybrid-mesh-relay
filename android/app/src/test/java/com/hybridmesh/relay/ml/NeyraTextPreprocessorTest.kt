package com.hybridmesh.relay.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class NeyraTextPreprocessorTest {
    @Test
    fun unicodeWhitespaceCollapsesLikePython() {
        assertEquals(
            "earthquake near kathmandu now",
            NeyraTextPreprocessor.cleanDisaster("Earthquake near Kathmandu　\tnow\u0085")
        )
    }

    @Test
    fun mentionsAndUrlsAreRemoved() {
        assertEquals("flood update", NeyraTextPreprocessor.cleanDisaster("@rescue_team flood https://x.co/a update"))
    }

    @Test
    fun mentionStopsAtCombiningMarkLikePython() {
        // Python's \w excludes Devanagari vowel signs, so @\w+ matches only "@र" in training.
        assertEquals("ाहत_दल flood", NeyraTextPreprocessor.cleanDisaster("@राहत_दल flood"))
    }

    /**
     * Android's java.util.regex rejects the UNICODE_CHARACTER_CLASS flag that the
     * desktop JVM accepts, so an embedded (?U) works in these JVM tests but breaks
     * model loading on phones. Keep it out of every ML regex.
     */
    @Test
    fun mlSourcesDoNotUseAndroidUnsupportedRegexFlags() {
        val offenders = File("src/main/java/com/hybridmesh/relay/ml").walk()
            .filter { it.extension == "kt" }
            .filter { file -> file.readText().contains("(?U)") || file.readText().contains("UNICODE_CHARACTER_CLASS") }
            .map { it.name }
            .toList()
        assertFalse("Android-unsupported regex flag in $offenders", offenders.isNotEmpty())
    }
}
