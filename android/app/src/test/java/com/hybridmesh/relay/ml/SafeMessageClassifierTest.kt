package com.hybridmesh.relay.ml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SafeMessageClassifierTest {
    private val advisory = MessageLocalClassifier.AdvisoryResult(
        disasterTypeDcT2 = "flood",
        disasterKeywordHint = "flood",
        humanitarianTopic = "Food and Medicine",
        misuseLabel = "benign_emergency_information",
        misuseRawLabel = "benign_emergency_information",
        disclaimer = MessageLocalClassifier.DISCLAIMER,
    )

    private fun classifier(
        timeoutMs: Long = 2_000,
        loads: AtomicInteger = AtomicInteger(),
        scores: AtomicInteger = AtomicInteger(),
        score: (String) -> MessageLocalClassifier.AdvisoryResult = { advisory },
    ) = SafeMessageClassifier(
        loadModel = {
            loads.incrementAndGet()
            val scorer: (String) -> MessageLocalClassifier.AdvisoryResult = { text ->
                scores.incrementAndGet()
                score(text)
            }
            scorer
        },
        dispatcher = Dispatchers.Default,
        timeoutMs = timeoutMs,
    )

    @Test
    fun returnsAdvisoryFromModel() = runBlocking {
        assertEquals(MessageClassification.Advisory(advisory), classifier().classify("m1", "water rising"))
    }

    @Test
    fun cachesResultPerMessageAndLoadsModelOnce() = runBlocking {
        val loads = AtomicInteger()
        val scores = AtomicInteger()
        val c = classifier(loads = loads, scores = scores)
        c.classify("m1", "water rising")
        c.classify("m1", "water rising")
        c.classify("m2", "another message")
        assertEquals(1, loads.get())
        assertEquals(2, scores.get())
    }

    @Test
    fun blankTextIsUnavailableWithoutLoadingModel() = runBlocking {
        val loads = AtomicInteger()
        assertEquals(MessageClassification.Unavailable("EMPTY_TEXT"), classifier(loads = loads).classify("m1", "  "))
        assertEquals(0, loads.get())
    }

    @Test
    fun modelLoadFailureFallsBackAndIsNotRetriedPerMessage() = runBlocking {
        val loads = AtomicInteger()
        val c = SafeMessageClassifier(
            loadModel = { loads.incrementAndGet(); throw IllegalStateException("asset missing") },
            dispatcher = Dispatchers.Default,
        )
        assertEquals(MessageClassification.Unavailable("MODEL_LOAD_FAILED"), c.classify("m1", "hello"))
        assertEquals(MessageClassification.Unavailable("MODEL_LOAD_FAILED"), c.classify("m2", "hello"))
        assertEquals(1, loads.get())
    }

    @Test
    fun inferenceFailureFallsBack() = runBlocking {
        val c = classifier(score = { throw RuntimeException("bad input") })
        assertEquals(MessageClassification.Unavailable("INFERENCE_FAILED"), c.classify("m1", "hello"))
    }

    @Test
    fun slowInferenceTimesOutInsteadOfBlocking() = runBlocking {
        val c = classifier(timeoutMs = 50, score = { Thread.sleep(500); advisory })
        val startedAt = System.nanoTime()
        val result = c.classify("m1", "hello")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(MessageClassification.Unavailable("TIMEOUT"), result)
        assertTrue("classify waited ${elapsedMs}ms", elapsedMs < 400)
    }

    @Test
    fun failedResultIsNotCachedSoALaterCallCanSucceed() = runBlocking {
        val calls = AtomicInteger()
        val c = classifier(score = { if (calls.incrementAndGet() == 1) throw RuntimeException("flaky") else advisory })
        assertEquals(MessageClassification.Unavailable("INFERENCE_FAILED"), c.classify("m1", "hello"))
        assertEquals(MessageClassification.Advisory(advisory), c.classify("m1", "hello"))
    }

    @Test
    fun shippedModelsClassifyEndToEnd() = runBlocking {
        val c = SafeMessageClassifier(
            loadModel = { MessageLocalClassifier.fromJson { File("src/main/assets/$it").readText() }::classify },
            dispatcher = Dispatchers.Default,
        )
        val result = c.classify("m1", "Earthquake near Kathmandu, buildings collapsed")
        assertTrue("got $result", result is MessageClassification.Advisory)
        assertEquals("earthquake", (result as MessageClassification.Advisory).result.disasterTypeDcT2)
    }
}
