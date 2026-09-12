package com.hybridmesh.relay.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelayNode
import com.hybridmesh.relay.ui.theme.RelayNodeInactive
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle

@Composable
fun HomeScreen(
    onMessagesClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    onNewMessage: () -> Unit,
    onMessageClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ----------------------------------------------------------------
        // HEADER
        // ----------------------------------------------------------------
        item {
            Spacer(
                modifier = Modifier.height(14.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "HYBRID MESH RELAY",
                        style = MaterialTheme.typography.displaySmall
                    )

                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )

                    Text(
                        text = "Resilient communication network",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(
                    modifier = Modifier.height(1.dp)
                )

                Text(
                    text = "PROFILE",
                    color = RelayAccent,
                    style = TechnicalTextStyle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(
                            onClick = onProfileClick
                        )
                        .padding(
                            horizontal = 8.dp,
                            vertical = 8.dp
                        )
                )
            }
        }

        // ----------------------------------------------------------------
        // NETWORK STATUS
        // ----------------------------------------------------------------
        item {
            NetworkStatusCard(
                onClick = onNetworkClick
            )
        }

        // ----------------------------------------------------------------
        // LOCAL MESH PREVIEW
        // ----------------------------------------------------------------
        item {
            MeshPreview()
        }

        // ----------------------------------------------------------------
        // RECENT MESSAGES HEADER
        // ----------------------------------------------------------------
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent messages",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "VIEW ALL",
                    color = RelayAccent,
                    style = TechnicalTextStyle,
                    modifier = Modifier.clickable(
                        onClick = onMessagesClick
                    )
                )
            }
        }

        // ----------------------------------------------------------------
        // RECENT MESSAGE 1
        // ----------------------------------------------------------------
        item {
            RecentMessage(
                sender = "Alex",
                preview = "Reached the campsite.",
                time = "10:42",
                transport = "BLE",
                state = "DELIVERED",
                onClick = onMessageClick
            )
        }

        // ----------------------------------------------------------------
        // RECENT MESSAGE 2
        // ----------------------------------------------------------------
        item {
            RecentMessage(
                sender = "Maya",
                preview = "Where are you?",
                time = "10:45",
                transport = "RELAY",
                state = "DELIVERED",
                onClick = onMessageClick
            )
        }

        // ----------------------------------------------------------------
        // RECENT MESSAGE 3
        // ----------------------------------------------------------------
        item {
            RecentMessage(
                sender = "Sam",
                preview = "I'll send the location shortly.",
                time = "11:02",
                transport = "MESH",
                state = "QUEUED",
                onClick = onMessageClick
            )
        }

        // ----------------------------------------------------------------
        // NEW MESSAGE BUTTON
        // ----------------------------------------------------------------
        item {
            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Button(
                onClick = onNewMessage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RelayAccent,
                    contentColor = RelayBackground
                )
            ) {
                Text(
                    text = "New Message",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(
                modifier = Modifier.height(12.dp)
            )
        }
    }
}

@Composable
private fun NetworkStatusCard(
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = RelaySurface,
                shape = RoundedCornerShape(18.dp)
            )
            .border(
                width = 1.dp,
                color = RelayBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(
                onClick = onClick
            )
            .padding(18.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            Column {
                Text(
                    text = "NETWORK STATUS",
                    color = RelayTextMuted,
                    style = TechnicalTextStyle
                )

                Spacer(
                    modifier = Modifier.height(6.dp)
                )

                Text(
                    text = "Connected",
                    color = RelayAccent,
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Text(
                text = "●",
                color = RelayAccent,
                style = MaterialTheme.typography.titleLarge
            )
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        StatusRow(
            label = "Internet",
            value = "AVAILABLE"
        )

        StatusRow(
            label = "Nearby devices",
            value = "03"
        )

        StatusRow(
            label = "Relay nodes",
            value = "01"
        )

        StatusRow(
            label = "Mesh",
            value = "READY"
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = TechnicalTextStyle
        )
    }
}

@Composable
private fun MeshPreview() {

    val transition = rememberInfiniteTransition(
        label = "meshPulse"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = RelaySurface,
                shape = RoundedCornerShape(18.dp)
            )
            .border(
                width = 1.dp,
                color = RelayBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
    ) {

        Text(
            text = "LOCAL MESH",
            color = RelayTextMuted,
            style = TechnicalTextStyle
        )

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
        ) {

            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {

                val center = androidx.compose.ui.geometry.Offset(
                    size.width * 0.50f,
                    size.height * 0.50f
                )

                val nodes = listOf(
                    androidx.compose.ui.geometry.Offset(
                        size.width * 0.18f,
                        size.height * 0.28f
                    ),
                    androidx.compose.ui.geometry.Offset(
                        size.width * 0.80f,
                        size.height * 0.26f
                    ),
                    androidx.compose.ui.geometry.Offset(
                        size.width * 0.80f,
                        size.height * 0.74f
                    ),
                    androidx.compose.ui.geometry.Offset(
                        size.width * 0.24f,
                        size.height * 0.76f
                    )
                )

                nodes.forEach { node ->
                    drawLine(
                        color = RelayNodeInactive.copy(
                            alpha = 0.7f
                        ),
                        start = center,
                        end = node,
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(10f, 10f)
                        )
                    )
                }

                drawCircle(
                    color = RelayAccent.copy(
                        alpha = pulse
                    ),
                    radius = 12.dp.toPx(),
                    center = center
                )

                nodes.forEach { node ->
                    drawCircle(
                        color = RelayNode,
                        radius = 7.dp.toPx(),
                        center = node
                    )
                }
            }

            Text(
                text = "YOU",
                modifier = Modifier.align(
                    Alignment.Center
                ),
                color = RelayBackground,
                style = TechnicalTextStyle
            )
        }

        Text(
            text = "4 reachable nodes",
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RecentMessage(
    sender: String,
    preview: String,
    time: String,
    transport: String,
    state: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = RelaySurface,
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = RelayBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(
                onClick = onClick
            )
            .padding(15.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {

        Column(
            modifier = Modifier.weight(1f)
        ) {

            Text(
                text = sender,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(
                modifier = Modifier.height(3.dp)
            )

            Text(
                text = preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Text(
                text = "$transport • $state",
                color = RelayTextMuted,
                style = TechnicalTextStyle
            )
        }

        Text(
            text = time,
            color = RelayTextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}