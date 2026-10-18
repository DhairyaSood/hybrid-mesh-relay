package com.hybridmesh.relay.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.data.NicknamePolicy
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.network.MeshTransportPreferences
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.ProfileViewModel
import com.hybridmesh.relay.ui.components.NodeIdText

@Composable
fun ProfileScreen(onLocationModeRequired: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ProfileViewModel = viewModel()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val mesh by MessagingManager.getInstance(context).mesh.collectAsStateWithLifecycle()
    val transportPreferences = MeshTransportPreferences.getInstance(context)
    val selection by transportPreferences.selection.collectAsStateWithLifecycle()
    val networkManager = NetworkManager.getInstance(context)
    var waitingForWifiDirectPermissionResult by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        networkManager.refresh()
        if (waitingForWifiDirectPermissionResult) {
            waitingForWifiDirectPermissionResult = false
            if (transportPreferences.selection.value.wifiDirectEnabled &&
                !PermissionManager.wifiDirectLocationModeEnabled(context)
            ) onLocationModeRequired()
        }
    }
    var deviceName by remember(identity.deviceName) { mutableStateOf(identity.deviceName) }
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
            Column(
                Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large)
                    .border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("MESH TRANSPORTS", style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    "Quick controls for Neyra. These switches do not change Bluetooth or Wi-Fi in Android settings.",
                    color = RelayTextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                ProfileTransportSwitch(
                    label = "Bluetooth LE",
                    status = when {
                        !selection.bleEnabled -> "App disabled"
                        mesh.availableTransports.contains("BLE_GATT") -> "Ready"
                        else -> "Enabled · waiting"
                    },
                    checked = selection.bleEnabled,
                    onCheckedChange = { enabled ->
                        transportPreferences.setBleEnabled(enabled)
                        if (enabled) {
                            val desired = selection.copy(bleEnabled = true)
                            val missing = PermissionManager.missingEnabledMeshPermissions(context, desired)
                            if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                        }
                    }
                )
                ProfileTransportSwitch(
                    label = "Wi-Fi Direct",
                    status = when {
                        !selection.wifiDirectEnabled -> "App disabled"
                        mesh.availableTransports.contains("WIFI_DIRECT") -> "Ready · ${mesh.connectedWifiPeers} peer(s)"
                        else -> "Enabled · ${mesh.wifiDirectStage.lowercase().replace('_', ' ')}"
                    },
                    checked = selection.wifiDirectEnabled,
                    onCheckedChange = { enabled ->
                        transportPreferences.setWifiDirectEnabled(enabled)
                        if (enabled) {
                            val desired = selection.copy(wifiDirectEnabled = true)
                            val missing = PermissionManager.missingEnabledMeshPermissions(context, desired)
                            if (missing.isNotEmpty()) {
                                waitingForWifiDirectPermissionResult = true
                                permissionLauncher.launch(missing)
                            } else if (!PermissionManager.wifiDirectLocationModeEnabled(context)) {
                                onLocationModeRequired()
                            }
                        }
                    }
                )
            }
        }
        item {
            InfoRow(label = "Node ID", value = identity.nodeId)
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
private fun ProfileTransportSwitch(
    label: String,
    status: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(status, color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.medium).border(1.dp, RelayBorder, MaterialTheme.shapes.medium).padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = RelayTextMuted)
            Spacer(Modifier.height(3.dp))
            if (label == "Node ID") NodeIdText(value, style = TechnicalTextStyle, color = RelayAccent, maxLines = 3)
            else Text(value, style = TechnicalTextStyle, color = RelayAccent, maxLines = 3)
        }
    }
}
