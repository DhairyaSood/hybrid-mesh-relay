package com.hybridmesh.relay.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridmesh.relay.R
import com.hybridmesh.relay.location.SosLocationResult
import com.hybridmesh.relay.permissions.LocationPermissionLevel
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.sos.SosPhase
import com.hybridmesh.relay.sos.SosUiState
import com.hybridmesh.relay.ui.theme.RelayEmergency
import com.hybridmesh.relay.ui.theme.RelayWarning
import com.hybridmesh.relay.ui.viewmodel.SosViewModel
import kotlin.math.abs

/**
 * Owns the whole SOS flow after the button is held: privacy notice (first time), location
 * permission, 5 s countdown, and the live status screen. Set [requested] to start it.
 */
@Composable
fun SosOverlay(viewModel: SosViewModel, requested: Boolean, onRequestHandled: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPrivacy by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.start() }

    fun begin() {
        val level = PermissionManager.location(context).level
        if (level == LocationPermissionLevel.PRECISE || level == LocationPermissionLevel.APPROXIMATE) {
            viewModel.start()
        } else {
            PermissionManager.markLocationRequestAttempted(context)
            permissionLauncher.launch(PermissionManager.locationPermissionsToRequest())
        }
    }

    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        onRequestHandled()
        if (state.phase != SosPhase.IDLE) return@LaunchedEffect
        if (viewModel.privacyNoticeAcknowledged()) begin() else showPrivacy = true
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text(stringResource(R.string.sos_privacy_title)) },
            text = { Text(stringResource(R.string.sos_privacy_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.acknowledgePrivacyNotice()
                        showPrivacy = false
                        begin()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RelayEmergency)
                ) { Text(stringResource(R.string.sos_privacy_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacy = false }) { Text(stringResource(R.string.sos_privacy_dismiss)) }
            }
        )
    }

    if (state.phase != SosPhase.IDLE) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF7A0A10))
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                if (state.phase == SosPhase.COUNTDOWN) {
                    CountdownContent(state.countdownSeconds, onCancel = viewModel::cancel)
                } else {
                    StatusContent(state, onStop = viewModel::stop, onClose = viewModel::dismiss)
                }
            }
        }
    }
}

@Composable
private fun CountdownContent(seconds: Int, onCancel: () -> Unit) {
    Text(stringResource(R.string.sos_countdown_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
    Text(
        stringResource(R.string.sos_countdown_seconds, seconds),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 120.sp
    )
    Text(
        stringResource(R.string.sos_countdown_caption),
        color = Color.White,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge
    )
    Button(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth().height(80.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF7A0A10))
    ) { Text(stringResource(R.string.sos_cancel), fontSize = 28.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun StatusContent(state: SosUiState, onStop: () -> Unit, onClose: () -> Unit) {
    val active = state.phase == SosPhase.ACTIVE
    Text(
        stringResource(if (active) R.string.sos_active_title else R.string.sos_stopped_title),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp
    )
    Text(
        stringResource(R.string.sos_status_line, state.sent, state.delivered, state.pending, state.failed),
        color = Color.White,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium
    )
    if (active && state.sent == 0) {
        Text(stringResource(R.string.sos_no_peers), color = Color.White, textAlign = TextAlign.Center)
    }
    state.location?.let { location ->
        Text(locationNote(location), color = Color.White, textAlign = TextAlign.Center)
        if (location.clockSkewSuspect) {
            val seconds = abs(location.clockSkewMs ?: 0L) / 1000
            Text(
                stringResource(R.string.sos_clock_skew_warning, seconds),
                color = RelayWarning,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
        }
    }
    if (state.unconfirmed > 0) {
        Text(
            stringResource(R.string.sos_unconfirmed, state.unconfirmed),
            color = RelayWarning,
            textAlign = TextAlign.Center
        )
    }
    if (active) {
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF7A0A10))
        ) { Text(stringResource(R.string.sos_stop), fontSize = 28.sp, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sos_close), color = Color.White)
        }
    }
}

@Composable
private fun locationNote(location: SosLocationResult): String = when {
    location.fix == null ->
        stringResource(R.string.sos_no_location, location.reason ?: "")
    (location.ageMs ?: 0L) > 0L ->
        stringResource(R.string.sos_location_approx_age, (location.ageMs ?: 0L) / 1000)
    else -> stringResource(R.string.sos_location_fresh)
}
