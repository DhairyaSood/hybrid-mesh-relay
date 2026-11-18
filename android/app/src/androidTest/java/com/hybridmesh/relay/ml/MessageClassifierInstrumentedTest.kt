package com.hybridmesh.relay.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads the shipped models from APK assets with Android's own regex and org.json,
 * which JVM unit tests cannot cover. Run with a phone attached:
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MessageClassifierInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun shippedModelsLoadAndClassifyOnDevice() {
        val result = MessageLocalClassifier(context).classify("Earthquake near Kathmandu, buildings collapsed")
        assertEquals("earthquake", result.disasterTypeDcT2)
    }

    @Test
    fun extortionMessageGetsAdvisoryThroughSafeClassifier() = runBlocking {
        val result = SafeMessageClassifier(loadModel = { MessageLocalClassifier(context)::classify })
            .classify("test-1", "send money now or your family will be hurt")
        assertTrue("got $result", result is MessageClassification.Advisory)
        assertEquals("coercion_or_extortion", (result as MessageClassification.Advisory).result.misuseLabel)
    }
}
