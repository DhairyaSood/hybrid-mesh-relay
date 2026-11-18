package com.hybridmesh.relay.ml

import android.content.Context
import com.hybridmesh.relay.diagnostics.MeshDbg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Result of classifying one message. Advisory only; never affects delivery. */
sealed interface MessageClassification {
    data class Advisory(val result: MessageLocalClassifier.AdvisoryResult) : MessageClassification
    /** No advisory: the UI shows nothing extra and the message is displayed as usual. */
    data class Unavailable(val reason: String) : MessageClassification
}

/** App-layer text classifier for received/displayed messages. Implementations must not throw. */
interface MessageClassifier {
    suspend fun classify(messageId: String, text: String): MessageClassification
}

/**
 * Loads the models once, off the main thread, and turns every failure into
 * [MessageClassification.Unavailable] so a broken or slow model can never block the UI,
 * sending or receiving.
 *
 * Reasons: EMPTY_TEXT, MODEL_LOAD_FAILED, INFERENCE_FAILED, TIMEOUT.
 */
class SafeMessageClassifier(
    loadModel: () -> (String) -> MessageLocalClassifier.AdvisoryResult,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val cacheSize: Int = DEFAULT_CACHE_SIZE,
) : MessageClassifier {
    // Own scope: a caller's timeout must not abort the one-time load, and work that
    // overruns the timeout finishes here instead of blocking the caller.
    private val loadScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val model: Deferred<(String) -> MessageLocalClassifier.AdvisoryResult> =
        loadScope.async(start = CoroutineStart.LAZY) {
            val startedAt = System.nanoTime()
            loadModel().also {
                MeshDbg.log("ML", "models loaded ms=${elapsedMs(startedAt)}")
            }
        }

    private val cache = object : LinkedHashMap<String, MessageClassification.Advisory>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MessageClassification.Advisory>) =
            size > cacheSize
    }

    override suspend fun classify(messageId: String, text: String): MessageClassification {
        val label = MeshDbg.node(messageId)
        if (text.isBlank()) return unavailable(label, "EMPTY_TEXT")
        synchronized(cache) { cache[messageId] }?.let { return it }

        val startedAt = System.nanoTime()
        val outcome = withTimeoutOrNull(timeoutMs) {
            val score = try {
                model.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return@withTimeoutOrNull unavailable(label, "MODEL_LOAD_FAILED", failure)
            }
            try {
                // async + await rather than withContext: CPU-bound scoring cannot be
                // interrupted, and withContext would hold the caller until it finished.
                MessageClassification.Advisory(loadScope.async { score(text) }.await())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                unavailable(label, "INFERENCE_FAILED", failure)
            }
        } ?: return unavailable(label, "TIMEOUT")

        if (outcome is MessageClassification.Advisory) {
            synchronized(cache) { cache[messageId] = outcome }
            val r = outcome.result
            MeshDbg.log(
                "ML",
                "classified msg=$label misuse=${r.misuseLabel} misuseRaw=${r.misuseRawLabel} " +
                    "disaster=${r.disasterTypeDcT2} keyword=${r.disasterKeywordHint} " +
                    "help=${r.humanitarianTopic} ms=${elapsedMs(startedAt)}"
            )
        }
        return outcome
    }

    private fun unavailable(label: String, reason: String, failure: Throwable? = null): MessageClassification.Unavailable {
        val detail = failure?.let { " error=${it.javaClass.simpleName}: ${it.message?.take(120)}" }.orEmpty()
        MeshDbg.log("ML", "unavailable msg=$label reason=$reason$detail")
        return MessageClassification.Unavailable(reason)
    }

    private fun elapsedMs(startedAtNanos: Long) = (System.nanoTime() - startedAtNanos) / 1_000_000

    companion object {
        /** Covers the first call, which also parses the models (~3.6 MB JSON). */
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val DEFAULT_CACHE_SIZE = 256
    }
}

/** One process-wide classifier so the models are parsed and held in memory once. */
object MessageClassifierProvider {
    @Volatile
    private var instance: MessageClassifier? = null

    fun get(context: Context): MessageClassifier =
        instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                SafeMessageClassifier(loadModel = { MessageLocalClassifier(appContext)::classify })
            }.also { instance = it }
        }
}
