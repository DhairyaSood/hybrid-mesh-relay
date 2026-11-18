package com.hybridmesh.relay.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hybridmesh.relay.R
import com.hybridmesh.relay.location.LocationActions
import com.hybridmesh.relay.location.LocationPayload
import com.hybridmesh.relay.sos.SosMessage
import com.hybridmesh.relay.ui.theme.RelayEmergency
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import java.util.Locale

/** High-priority red bubble for an SOS alert or its cancellation. Tap opens the location in maps. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SosBubble(sos: SosMessage, incoming: Boolean, onLongPress: () -> Unit = {}) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    val alert = sos as? SosMessage.Alert
    val cancelled = sos is SosMessage.Cancelled
    val borderColor = if (cancelled) RelayTextMuted else RelayEmergency
    val fill = if (cancelled) Color.Transparent else RelayEmergency.copy(alpha = 0.22f)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End
    ) {
        Column(
            Modifier
                .widthIn(max = 310.dp)
                .background(fill, shape)
                .border(2.dp, borderColor, shape)
                .padding(12.dp)
                .combinedClickable(
                    onClick = {
                        if (alert?.hasLocation == true) {
                            LocationActions.openInMaps(
                                context,
                                LocationPayload(
                                    alert.latitude!!,
                                    alert.longitude!!,
                                    alert.accuracyMeters?.toFloat(),
                                    System.currentTimeMillis()
                                )
                            )
                        }
                    },
                    onLongClick = onLongPress
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (sos) {
                is SosMessage.Cancelled -> {
                    Text(stringResource(R.string.sos_bubble_cancelled), fontWeight = FontWeight.Bold, color = RelayTextMuted)
                    Text(stringResource(R.string.sos_bubble_cancelled_body))
                    SosTimes(sos.utcTime, sos.localTime)
                }
                is SosMessage.Alert -> {
                    Text(stringResource(R.string.sos_bubble_title), fontWeight = FontWeight.Bold, color = RelayEmergency, style = MaterialTheme.typography.titleLarge)
                    if (sos.hasLocation) {
                        Text(
                            stringResource(
                                R.string.sos_bubble_coordinates,
                                String.format(Locale.US, "%.6f", sos.latitude),
                                String.format(Locale.US, "%.6f", sos.longitude)
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        sos.accuracyMeters?.let { Text(stringResource(R.string.sos_bubble_accuracy, it), style = MaterialTheme.typography.bodySmall) }
                        sos.fixAgeSeconds?.let { Text(stringResource(R.string.sos_bubble_fix_age, it), style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.sos_bubble_open_maps), style = MaterialTheme.typography.bodySmall, color = RelayTextMuted)
                    } else {
                        Text(stringResource(R.string.sos_bubble_no_location, sos.noLocationReason ?: ""))
                    }
                    SosTimes(sos.utcTime, sos.localTime)
                }
            }
        }
    }
}

@Composable
private fun SosTimes(utc: String, local: String) {
    if (utc.isEmpty() && local.isEmpty()) return
    Text(
        stringResource(R.string.sos_bubble_time, local, utc),
        style = MaterialTheme.typography.labelSmall,
        color = RelayTextMuted
    )
}
