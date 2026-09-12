package com.hybridmesh.relay.ui

import android.Manifest
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.hybridmesh.relay.ble.BlePeer
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

    val viewModel: DevicesViewModel =
        viewModel()

    val peers by
        viewModel.peers.collectAsStateWithLifecycle()

    val advertising by
        viewModel.isAdvertising.collectAsStateWithLifecycle()

    val advertisingErrorCode by
        viewModel.advertisingErrorCode
            .collectAsStateWithLifecycle()

    var permissionsGranted by remember {
        mutableStateOf(
            hasBlePermissions(context)
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .RequestMultiplePermissions()
        ) { permissions ->

            permissionsGranted =
                requiredBlePermissions()
                    .all { permission ->
                        permissions[permission] == true
                    }

            if (permissionsGranted) {
                viewModel.start()
            }
        }

    DisposableEffect(permissionsGranted) {

        if (permissionsGranted) {
            viewModel.start()
        }

        onDispose {
            viewModel.stopScanning()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                RelayBackground
            )
            .padding(
                horizontal = 18.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(16.dp)
    ) {

        // -------------------------------------------------------------
        // HEADER
        // -------------------------------------------------------------

        item {

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            Text(
                text = "DEVICES",
                style =
                    MaterialTheme.typography.headlineMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Text(
                text =
                    "Nearby Hybrid Mesh Relay nodes discovered over BLE.",
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,
                style =
                    MaterialTheme.typography.bodyMedium
            )
        }

        // -------------------------------------------------------------
        // LOCAL DEVICE
        // -------------------------------------------------------------

        item {

            LocalDeviceCard(
                nodeId = viewModel.identity.nodeId,
                deviceName =
                    viewModel.identity.deviceName,
                advertising = advertising
            )
        }

        // -------------------------------------------------------------
        // ADVERTISING ERROR
        // -------------------------------------------------------------

        if (
            advertisingErrorCode != null &&
            !advertising
        ) {

            item {

                BleAdvertisingErrorCard(
                    errorCode =
                        advertisingErrorCode
                )
            }
        }

        // -------------------------------------------------------------
        // PERMISSIONS
        // -------------------------------------------------------------

        if (!permissionsGranted) {

            item {

                PermissionCard(
                    onRequestPermissions = {

                        permissionLauncher.launch(
                            requiredBlePermissions()
                        )
                    }
                )
            }

        } else {

            // ---------------------------------------------------------
            // BLE CONTROLS
            // ---------------------------------------------------------

            item {

                ScannerControlCard(
                    scanning =
                        viewModel.isScanning(),
                    deviceCount =
                        peers.size,
                    onStart = {
                        viewModel.startScanning()
                    },
                    onStop = {
                        viewModel.stopScanning()
                    },
                    onBluetoothSettings = {

                        context.startActivity(
                            Intent(
                                Settings.ACTION_BLUETOOTH_SETTINGS
                            )
                        )
                    }
                )
            }

            // ---------------------------------------------------------
            // NEARBY DEVICES HEADER
            // ---------------------------------------------------------

            item {

                Text(
                    text =
                        "NEARBY HYBRID MESH NODES",
                    style =
                        TechnicalTextStyle,
                    color =
                        RelayTextMuted,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            // ---------------------------------------------------------
            // DEVICE LIST
            // ---------------------------------------------------------

            if (peers.isEmpty()) {

                item {

                    EmptyDevicesCard()
                }

            } else {

                items(
                    items = peers,
                    key = { peer ->
                        peer.nodeId
                    }
                ) { peer ->

                    BlePeerCard(
                        peer = peer
                    )
                }
            }
        }

        item {

            Spacer(
                modifier = Modifier.height(12.dp)
            )
        }
    }
}

@Composable
private fun LocalDeviceCard(
    nodeId: String,
    deviceName: String,
    advertising: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(18.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(18.dp)
            )
            .padding(18.dp)
    ) {

        Text(
            text = "LOCAL DEVICE",
            style =
                TechnicalTextStyle,
            color =
                RelayTextMuted,
            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        Text(
            text = deviceName,
            style =
                MaterialTheme.typography.titleLarge,
            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(4.dp)
        )

        Text(
            text = nodeId,
            style = TechnicalTextStyle,
            color = RelayAccent
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        StatusLine(
            label = "Device type",
            value = "PHONE"
        )

        StatusLine(
            label = "BLE advertising",
            value =
                if (advertising) {
                    "ACTIVE"
                } else {
                    "INACTIVE"
                }
        )
    }
}

@Composable
private fun BleAdvertisingErrorCard(
    errorCode: Int?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(16.dp)
    ) {

        Text(
            text =
                "BLE ADVERTISING UNAVAILABLE",
            style =
                TechnicalTextStyle,
            color =
                RelayTextMuted,
            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Text(
            text =
                when (errorCode) {

                    -100 ->
                        "Bluetooth advertising permission has not been granted."

                    -101 ->
                        "Bluetooth is not available on this device."

                    -102 ->
                        "Bluetooth is turned off."

                    -104 ->
                        "This device does not support BLE advertising."

                    -105 ->
                        "The Android BLE advertiser is unavailable."

                    1 ->
                        "Advertiser failed: data is too large."

                    2 ->
                        "Advertiser failed: too many advertisers are active."

                    3 ->
                        "Advertiser failed: internal Android error."

                    4 ->
                        "Advertiser failed: Android reports advertising is unsupported."

                    else ->
                        "Android reported BLE advertising error code $errorCode."
                },
            style =
                MaterialTheme.typography.bodyMedium,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionCard(
    onRequestPermissions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(18.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(18.dp)
            )
            .padding(18.dp)
    ) {

        Text(
            text =
                "BLUETOOTH ACCESS REQUIRED",
            style =
                TechnicalTextStyle,
            color =
                RelayTextMuted,
            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        Text(
            text =
                "Bluetooth access is required to discover nearby Hybrid Mesh Relay devices.",
            style =
                MaterialTheme.typography.bodyMedium
        )

        Spacer(
            modifier = Modifier.height(14.dp)
        )

        Button(
            onClick =
                onRequestPermissions,
            modifier =
                Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        RelayAccent,
                    contentColor =
                        RelayBackground
                )
        ) {
            Text(
                text =
                    "ENABLE BLUETOOTH ACCESS"
            )
        }
    }
}

@Composable
private fun ScannerControlCard(
    scanning: Boolean,
    deviceCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBluetoothSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(18.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(18.dp)
            )
            .padding(18.dp)
    ) {

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    text =
                        "BLE DISCOVERY",
                    style =
                        TechnicalTextStyle,
                    color =
                        RelayTextMuted,
                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(6.dp)
                )

                Text(
                    text =
                        if (scanning) {
                            "Scanning nearby devices"
                        } else {
                            "Scanner idle"
                        },
                    style =
                        MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text =
                    "$deviceCount FOUND",
                style =
                    TechnicalTextStyle,
                color =
                    RelayAccent,
                fontWeight =
                    FontWeight.Bold
            )
        }

        Spacer(
            modifier =
                Modifier.height(14.dp)
        )

        Button(
            onClick = {

                if (scanning) {
                    onStop()
                } else {
                    onStart()
                }
            },
            modifier =
                Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        if (scanning) {
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                        } else {
                            RelayAccent
                        },
                    contentColor =
                        if (scanning) {
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                        } else {
                            RelayBackground
                        }
                )
        ) {

            Text(
                text =
                    if (scanning) {
                        "STOP SCANNING"
                    } else {
                        "START SCANNING"
                    }
            )
        }

        Spacer(
            modifier =
                Modifier.height(8.dp)
        )

        Button(
            onClick =
                onBluetoothSettings,
            modifier =
                Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                )
        ) {

            Text(
                text =
                    "BLUETOOTH SETTINGS"
            )
        }
    }
}

@Composable
private fun BlePeerCard(
    peer: BlePeer
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(16.dp),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(CircleShape)
                .background(
                    RelayAccent
                )
        )

        Spacer(
            modifier =
                Modifier.width(14.dp)
        )

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(
                text = peer.deviceName,
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier =
                    Modifier.height(3.dp)
            )

            Text(
                text = peer.nodeId,
                style =
                    TechnicalTextStyle,
                color =
                    RelayAccent
            )

            Spacer(
                modifier =
                    Modifier.height(5.dp)
            )

            Text(
                text =
                    "${peer.deviceType.name} • ${peer.address}",
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    RelayTextMuted
            )
        }

        Text(
            text =
                "${peer.rssi} dBm",
            style =
                TechnicalTextStyle,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyDevicesCard() {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                RelaySurface,
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                RelayBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(20.dp)
    ) {

        Text(
            text =
                "NO HYBRID MESH DEVICES FOUND",
            style =
                TechnicalTextStyle,
            color =
                RelayTextMuted,
            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier =
                Modifier.height(8.dp)
        )

        Text(
            text =
                "Start scanning and place another Hybrid Mesh Relay device nearby.",
            style =
                MaterialTheme.typography.bodyMedium,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {

        Text(
            text = label,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,
            style =
                MaterialTheme.typography.bodyMedium
        )

        Text(
            text = value,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurface,
            style =
                TechnicalTextStyle
        )
    }
}

private fun requiredBlePermissions(): Array<String> {

    return if (
        Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.S
    ) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}

private fun hasBlePermissions(
    context: Context
): Boolean {

    return requiredBlePermissions()
        .all { permission ->

            ContextCompat.checkSelfPermission(
                context,
                permission
            ) ==
                PackageManager.PERMISSION_GRANTED
        }
}