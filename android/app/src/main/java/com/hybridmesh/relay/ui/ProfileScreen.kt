package com.hybridmesh.relay.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.data.NicknamePolicy
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.ProfileViewModel

@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    val viewModel: ProfileViewModel = viewModel()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    var deviceName by remember(identity.deviceName) { mutableStateOf(identity.deviceName) }
    val clipboard = LocalClipboardManager.current
    val cleanedUsername = NicknamePolicy.clean(deviceName)
    val validationError = NicknamePolicy.errorMessage(deviceName)
    val dirty = cleanedUsername != identity.deviceName && NicknamePolicy.isValid(cleanedUsername)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RelayBackground),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("DEVICE PROFILE", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text("Your username is public to nearby devices. Your Node ID is the persistent address.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Column(Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large).border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LOCAL IDENTITY", style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = NicknamePolicy.clean(it) },
                    isError = deviceName.isNotEmpty() && validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Username") },
                    supportingText = {
                        Text(validationError ?: "Up to ${NicknamePolicy.MAX_LENGTH} characters. Use letters, numbers, _ . - @ & \$ ! # ^ ~.")
                    }
                )
                Button(
                    onClick = {
                        viewModel.updateDeviceName(cleanedUsername)
                        Toast.makeText(context, "Username updated", Toast.LENGTH_SHORT).show()
                    },
                    enabled = dirty,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("SAVE CHANGES") }
            }
        }
        item {
            InfoRow(label = "Node ID", value = identity.nodeId) {
                clipboard.setText(AnnotatedString(identity.nodeId))
                Toast.makeText(context, "Node ID copied", Toast.LENGTH_SHORT).show()
            }
        }
        item { InfoRow("Device type", identity.deviceType.name) }
        item { InfoRow("App version", viewModel.appVersion) }
        item {
            Text(
                "Node ID is generated locally and persisted on this installation. Updating the username does not change it.",
                color = RelayTextMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.medium).border(1.dp, RelayBorder, MaterialTheme.shapes.medium).padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = RelayTextMuted)
            Spacer(Modifier.height(3.dp))
            Text(value, style = TechnicalTextStyle, color = RelayAccent, maxLines = 3)
        }
        if (onAction != null) {
            OutlinedButton(onClick = onAction) { Text("COPY") }
        }
    }
}
