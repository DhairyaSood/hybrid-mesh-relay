package com.hybridmesh.relay.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.ble.BleOperationState
import com.hybridmesh.relay.ble.BlePeer
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.DevicesViewModel

@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val viewModel: DevicesViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()

    var permissionsGranted by remember {
        mutableStateOf(hasRequiredBlePermissions(context))
    }
    var showBluetoothDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsGranted = hasRequiredBlePermissions(context)
        viewModel.refresh()
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissionsGranted = hasRequiredBlePermissions(context)
        viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        permissionsGranted = hasRequiredBlePermissions(context)
        viewModel.refresh()
    }

    LaunchedEffect(state.bluetoothState) {
        if (state.bluetoothState == BluetoothState.OFF && state.networkEnabled) {
            showBluetoothDialog = true
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayBackground),
        contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "DEVICES",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = "Your node and currently discovered peers.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item {
            LocalDeviceCard(
                name = identity.deviceName,
                nodeId = identity.nodeId,
                bluetoothState = state.bluetoothState,
                advertisingState = state.advertisingState
            )
        }

        if (!state.bleSupported) {
            item {
                InfoCard(
                    "BLE NOT AVAILABLE",
                    "This phone does not report Bluetooth Low Energy capability."
                )
            }
        } else if (!permissionsGranted) {
            item {
                InfoCardWithButton(
                    title = "BLUETOOTH ACCESS REQUIRED",
                    body = "Grant Bluetooth scan, advertise, and connect access to use the mesh.",
                    button = "ENABLE BLUETOOTH ACCESS",
                    onClick = {
                        permissionLauncher.launch(requiredBlePermissions())
                    }
                )
            }
        } else if (state.bluetoothState == BluetoothState.OFF) {
            item {
                InfoCardWithButton(
                    title = "BLUETOOTH IS OFF",
                    body = "Discovery and BLE advertising are paused until Bluetooth is enabled.",
                    button = "TURN BLUETOOTH ON",
                    onClick = {
                        enableBluetoothLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        )
                    }
                )
            }
        }

        if (state.advertisingState == BleOperationState.ERROR) {
            item {
                InfoCard(
                    "BLE ADVERTISING ERROR",
                    advertisingErrorMessage(state.advertisingErrorCode)
                )
            }
        }

        if (state.scanningState == BleOperationState.ERROR) {
            item {
                InfoCard(
                    "BLE SCANNING ERROR",
                    scanningErrorMessage(state.scanningErrorCode)
                )
            }
        }

        item {
            ScannerControlCard(
                scanning = state.scanningState == BleOperationState.ACTIVE,
                starting = state.scanningState == BleOperationState.STARTING,
                count = state.nearbyDeviceCount,
                onSettings = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NEARBY HYBRID MESH NODES",
                    style = TechnicalTextStyle,
                    color = RelayTextMuted,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${state.nearbyDeviceCount} FOUND",
                    style = TechnicalTextStyle,
                    color = RelayAccent,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (state.peers.isEmpty()) {
            item {
                InfoCard(
                    if (state.scanningState == BleOperationState.ACTIVE) {
                        "SEARCHING FOR MESH NODES"
                    } else {
                        "NO MESH NODES FOUND"
                    },
                    if (state.scanningState == BleOperationState.ACTIVE) {
                        "Nearby devices will appear here when fresh BLE advertisements are received."
                    } else {
                        "Discovery is controlled by the mesh service and may be temporarily unavailable while Bluetooth or permissions are changing."
                    }
                )
            }
        } else {
            items(state.peers, key = { it.nodeId }) { peer ->
                PeerCard(peer)
            }
        }
    }

    if (showBluetoothDialog) {
        AlertDialog(
            onDismissRequest = { showBluetoothDialog = false },
            title = { Text("BLUETOOTH OFF") },
            text = {
                Text(
                    "Hybrid Mesh Relay has stopped BLE scanning and advertising because Bluetooth is disabled."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBluetoothDialog = false
                        enableBluetoothLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        )
                    }
                ) {
                    Text("TURN ON")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBluetoothDialog = false }) {
                    Text("LATER")
                }
            }
        )
    }
}

@Composable
private fun LocalDeviceCard(
    name: String,
    nodeId: String,
    bluetoothState: BluetoothState,
    advertisingState: BleOperationState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RelaySurface, RoundedCornerShape(18.dp))
            .border(1.dp, RelayBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("LOCAL DEVICE", style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(nodeId, style = TechnicalTextStyle, color = RelayAccent, maxLines = 1)
        StatusLine("Bluetooth", bluetoothState.name)
        StatusLine("BLE advertising", advertisingState.name)
    }
}

@Composable
private fun ScannerControlCard(
    scanning: Boolean,
    starting: Boolean,
    count: Int,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RelaySurface, RoundedCornerShape(18.dp))
            .border(1.dp, RelayBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "BLE DISCOVERY",
                    style = TechnicalTextStyle,
                    color = RelayTextMuted,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    when {
                        starting -> "Starting scanner…"
                        scanning -> "Scanning nearby devices"
                        else -> "Discovery is waiting for the mesh runtime"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                "$count FOUND",
                style = TechnicalTextStyle,
                color = RelayAccent,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            "Discovery is managed by the background mesh service and remains independent of this screen.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            onClick = onSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text("BLUETOOTH SETTINGS")
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RelaySurface, RoundedCornerShape(16.dp))
            .border(1.dp, RelayBorder, RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoCardWithButton(
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RelaySurface, RoundedCornerShape(16.dp))
            .border(1.dp, RelayBorder, RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = RelayAccent,
                contentColor = RelayBackground
            )
        ) {
            Text(button)
        }
    }
}

@Composable
private fun PeerCard(peer: BlePeer) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RelaySurface, RoundedCornerShape(16.dp))
            .border(1.dp, RelayBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(CircleShape)
                .background(RelayAccent)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(peer.nodeId, style = TechnicalTextStyle, color = RelayAccent, maxLines = 1)
            Text(
                "${peer.deviceType.name} • DISCOVERED",
                style = MaterialTheme.typography.bodySmall,
                color = RelayTextMuted
            )
        }
        Text("${peer.rssi} dBm", style = TechnicalTextStyle, color = RelayTextMuted, maxLines = 1)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = TechnicalTextStyle)
    }
}

private fun requiredBlePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}

private fun hasRequiredBlePermissions(context: Context): Boolean =
    requiredBlePermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

private fun isBluetoothEnabled(context: Context): Boolean =
    runCatching {
        context
            .getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?.adapter
            ?.isEnabled == true
    }.getOrDefault(false)

private fun advertisingErrorMessage(errorCode: Int?): String = when (errorCode) {
    -100 -> "Bluetooth advertising permission is missing."
    -101 -> "Bluetooth is not available on this device."
    -102 -> "Bluetooth is currently turned off."
    -103 -> "This device does not provide BLE hardware."
    -105 -> "Android could not provide a BLE advertiser."
    -106 -> "The advertising payload was rejected as invalid."
    1 -> "Android rejected the advertisement because the data was too large."
    2 -> "Too many BLE advertisers are currently active."
    3 -> "Android reported an internal advertising error."
    4, 5 -> "Android reported BLE advertising as unsupported."
    else -> "Android reported advertising error code $errorCode."
}

private fun scanningErrorMessage(errorCode: Int?): String = when (errorCode) {
    -200 -> "Bluetooth scan permission is missing."
    -201 -> "Bluetooth is not available on this device."
    -202 -> "Bluetooth is currently turned off."
    -203 -> "This device does not provide BLE hardware."
    -204 -> "Android could not provide a BLE scanner."
    -205 -> "The BLE scan settings were rejected."
    1 -> "A BLE scan is already active."
    2 -> "Android could not register the scan."
    3 -> "Android reported an internal scan error."
    4, 5 -> "Android reported BLE scanning as unsupported."
    else -> "Android reported scan error code $errorCode."
}
