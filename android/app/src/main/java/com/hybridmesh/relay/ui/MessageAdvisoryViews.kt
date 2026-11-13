package com.hybridmesh.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hybridmesh.relay.messaging.data.MessageRecordEntity
import com.hybridmesh.relay.ml.AdvisoryPresentation
import com.hybridmesh.relay.ml.MessageClassification
import com.hybridmesh.relay.ui.theme.RelayTextMuted

/** Classifies [message] off the main thread; null until a result (or fallback) arrives. */
@Composable
fun rememberMessageAdvisory(
    message: MessageRecordEntity,
    classify: suspend (MessageRecordEntity) -> MessageClassification,
): State<MessageClassification?> =
    produceState<MessageClassification?>(initialValue = null, message.messageId, message.content) {
        // Defense in depth: SOS alerts are never classified (see AdvisoryPresentation.shouldClassify).
        value = if (AdvisoryPresentation.shouldClassify(message.messageType, message.content)) {
            classify(message)
        } else {
            null
        }
    }

/** Small line under an incoming bubble; renders nothing for ordinary messages or fallbacks. */
@Composable
fun MessageAdvisoryChip(classification: MessageClassification?) {
    val advisory = (classification as? MessageClassification.Advisory)?.result ?: return
    val text = AdvisoryPresentation.chipText(advisory) ?: return
    Text(
        text = text,
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = RelayTextMuted,
    )
}

/** "LOCAL ADVISORY" section for the long-press diagnostics dialog. */
@Composable
fun MessageAdvisorySection(classification: MessageClassification?) {
    when (classification) {
        null -> return
        is MessageClassification.Unavailable -> {
            val text = AdvisoryPresentation.unavailableText(classification.reason) ?: return
            Divider()
            AdvisoryTitle()
            Text(text, style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
        }
        is MessageClassification.Advisory -> {
            Divider()
            AdvisoryTitle()
            AdvisoryPresentation.detailRows(classification.result).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(classification.result.disclaimer, style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
        }
    }
}

@Composable
private fun AdvisoryTitle() {
    Text("LOCAL ADVISORY · OFFLINE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}
