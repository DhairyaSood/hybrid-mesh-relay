package com.hybridmesh.relay.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hybridmesh.relay.R
import com.hybridmesh.relay.ui.theme.RelayEmergency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

const val SOS_HOLD_MILLIS = 2_000

/** Red SOS button: hold for 2 s while a ring fills; releasing early resets it. */
@Composable
fun SosButton(onTriggered: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val description = stringResource(R.string.sos_button_description)
    Box(
        modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .background(RelayEmergency)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val fill = scope.launch {
                        progress.animateTo(1f, tween(SOS_HOLD_MILLIS, easing = LinearEasing))
                        onTriggered()
                        progress.snapTo(0f)
                    }
                    val up = try {
                        waitForUpOrCancellation()
                    } catch (cancelled: CancellationException) {
                        fill.cancel()
                        scope.launch { progress.snapTo(0f) }
                        throw cancelled
                    }
                    if (fill.isActive) {
                        fill.cancel()
                        scope.launch { progress.snapTo(0f) }
                    }
                    up?.consume()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(48.dp)) {
            val stroke = 4.dp.toPx()
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * progress.value,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Text(
            stringResource(R.string.sos_button_label),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}
