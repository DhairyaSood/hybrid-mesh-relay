package com.hybridmesh.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.messaging.data.AttachmentRecordEntity
import com.hybridmesh.relay.messaging.mesh.MeshPeer
import com.hybridmesh.relay.messaging.mesh.MeshRuntimeSnapshot
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkDiagnosticEvent
import com.hybridmesh.relay.network.NetworkDiagnosticsSnapshot
import com.hybridmesh.relay.network.NetworkEventKind
import com.hybridmesh.relay.network.NetworkState
import com.hybridmesh.relay.network.percentile
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelayBackground
import com.hybridmesh.relay.ui.theme.RelayBorder
import com.hybridmesh.relay.ui.theme.RelaySurface
import com.hybridmesh.relay.ui.theme.RelayTextMuted
import com.hybridmesh.relay.ui.theme.TechnicalTextStyle
import com.hybridmesh.relay.ui.viewmodel.MessagesViewModel
import com.hybridmesh.relay.ui.components.rememberWifiEnabled
import java.util.Locale

private val OVERVIEW_WINDOWS = listOf(
    null to "ALL",
    5 * 60 * 1_000L to "5 MIN",
    15 * 60 * 1_000L to "15 MIN",
    60 * 60 * 1_000L to "1 HOUR"
)

@Composable
fun NetworkScreen() {
    val context = LocalContext.current
    val viewModel: MessagesViewModel = viewModel()
    val state by viewModel.networkState.collectAsStateWithLifecycle()
    val meshPeers by viewModel.meshPeers.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val diagnostics by viewModel.networkDiagnostics.collectAsStateWithLifecycle()
    val messagingManager = MessagingManager.getInstance(context)
    val transport by messagingManager.transport.collectAsStateWithLifecycle()
    val mesh by messagingManager.mesh.collectAsStateWithLifecycle()
    val wifiRadioEnabled = rememberWifiEnabled()
    val wifiPermissionGranted = rememberWifiDirectPermissionGranted()
    val wifiPermissionName = if (PermissionManager.requiresNearbyWifiDevicesPermission(context)) "NEARBY_WIFI_DEVICES" else "ACCESS_FINE_LOCATION"
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var overviewWindowIndex by rememberSaveable { mutableIntStateOf(1) }

    Column(Modifier.fillMaxSize().background(RelayBackground)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text("NETWORK", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Live mesh health and measured transport activity.",
                color = RelayTextMuted
            )
            Row(Modifier.padding(top = 8.dp)) {
                listOf("OVERVIEW", "ACTIVITY", "ADVANCED").forEachIndexed { index, label ->
                    TextButton(onClick = { selectedTab = index }) {
                        Text(label, color = if (selectedTab == index) RelayAccent else RelayTextMuted)
                    }
                }
            }
        }
        when (selectedTab) {
            0 -> Overview(
                state = state,
                mesh = mesh,
                peers = meshPeers,
                diagnostics = diagnostics,
                wifiRadioEnabled = wifiRadioEnabled,
                windowMs = OVERVIEW_WINDOWS[overviewWindowIndex].first,
                windowLabel = OVERVIEW_WINDOWS[overviewWindowIndex].second,
                onWindowChange = { overviewWindowIndex = it }
            )
            1 -> Activity(diagnostics)
            else -> Advanced(state, transport, mesh, diagnostics, wifiRadioEnabled, wifiPermissionGranted, wifiPermissionName, meshPeers, attachments)
        }
    }
}

@Composable
private fun Overview(
    state: NetworkState,
    mesh: MeshRuntimeSnapshot,
    peers: List<com.hybridmesh.relay.messaging.mesh.MeshPeer>,
    diagnostics: NetworkDiagnosticsSnapshot,
    wifiRadioEnabled: Boolean,
    windowMs: Long?,
    windowLabel: String,
    onWindowChange: (Int) -> Unit
) {
    val now = System.currentTimeMillis()
    val events = windowMs?.let { diagnostics.inWindow(now, it) } ?: diagnostics.events
    val requests = events.filter { it.operation == "mesh.send" && isCompletedSendAttempt(it) }
    val successful = requests.count { it.kind == NetworkEventKind.REQUEST_SUCCEEDED }
    val failures = requests.count {
        it.kind == NetworkEventKind.REQUEST_FAILED || it.kind == NetworkEventKind.REQUEST_UNAVAILABLE
    }
    val durations = requests.mapNotNull { it.durationMs }
    val active = activeRequestEvents(events).size
    val windowDetail = if (windowMs == null) {
        "all ${events.size} retained events · ${diagnostics.droppedEvents} older events dropped"
    } else {
        "${events.size} events in window · ${diagnostics.events.size} retained"
    }
    val bleHasError = mesh.bleEnabled && (
        state.bleRuntimeState == com.hybridmesh.relay.network.BleRuntimeState.DEGRADED ||
            state.advertisingErrorCode != null || state.scanningErrorCode != null
        )
    val wifiHasError = mesh.wifiDirectEnabled && (mesh.wifiDirectLastError != null || mesh.wifiDirectStage == "DEGRADED")
    val currentRuntimeError = bleHasError || wifiHasError
    val startupBlocked = (mesh.bleEnabled && (
        !state.bleSupported || !state.blePermissions.allGranted ||
            state.bluetoothState != BluetoothState.ON
        )) || (mesh.wifiDirectEnabled && (!wifiRadioEnabled || !mesh.wifiDirectLocationModeEnabled))
    val health = when {
        !mesh.running || (!mesh.bleEnabled && !mesh.wifiDirectEnabled) -> "OFFLINE"
        mesh.availableTransports.isNotEmpty() -> "MESH ACTIVE"
        currentRuntimeError || startupBlocked -> "NEEDS ATTENTION"
        else -> "STARTING / WAITING"
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard("ONE MESH · LIVE") {
                MetricLine("STATE", health, "One peer graph; carrier selected for each next hop")
                MetricLine("ACTIVE CARRIERS", mesh.availableTransports.ifEmpty { listOf("NONE") }.joinToString(" · "), "BLE and Wi-Fi Direct can be enabled independently")
                MetricLine("WINDOW", windowLabel, windowDetail)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    OVERVIEW_WINDOWS.forEachIndexed { index, (_, label) ->
                        TextButton(onClick = { onWindowChange(index) }) {
                            Text(label, color = if (index == OVERVIEW_WINDOWS.indexOfFirst { it.second == windowLabel }) RelayAccent else RelayTextMuted)
                        }
                    }
                }
            }
        }
        item {
            SectionCard("TRAFFIC · MEASURED") {
                MetricLine("COMPLETED ATTEMPTS", requests.size.toString(), "$successful next-hop accepted · $failures failed / unavailable")
                MetricLine("ACCEPTANCE RATE", if (requests.isEmpty()) "—" else "${(successful * 100.0 / requests.size).oneDecimal()}%", "next-hop acceptance · does not mean destination delivery")
                MetricLine("ACTIVE ATTEMPTS", active.coerceAtLeast(0).toString(), "in-progress sends or connection setups without a terminal result")
                MetricLine("RETRIES", requests.count { (it.attempt ?: 1) > 1 }.toString(), "attempts after the first candidate")
            }
        }
        item {
            SectionCard("PERFORMANCE · MEASURED") {
                MetricLine("MEDIAN LATENCY", percentile(durations, 0.5)?.let { "$it ms" } ?: "—", "${durations.size} completed samples")
                MetricLine("P95 LATENCY", percentile(durations, 0.95)?.let { "$it ms" } ?: "—", "upper-tail completed latency")
                MetricLine("BYTES ATTEMPTED", formatBytes(requests.sumOf { it.bytes }), "wire payload bytes supplied to transports")
            }
        }
        item {
            SectionCard("MESH RUNTIME · LIVE") {
                MetricLine("MESH PEERS", peers.size.toString(), "unique Neyra identities across all enabled carriers")
                MetricLine("BLUETOOTH RADIO", bluetoothLabel(state.bluetoothState), "App BLE ${if (mesh.bleEnabled) "enabled" else "disabled"}")
                MetricLine("WI-FI RADIO", if (wifiRadioEnabled) "ON" else "OFF", "App Wi-Fi Direct ${if (mesh.wifiDirectEnabled) "enabled" else "disabled"}")
                MetricLine("LOCATION MODE", if (mesh.wifiDirectLocationModeEnabled) "ON" else "OFF", "Required by Android Wi-Fi Direct service discovery")
                MetricLine("INTERNET", if (state.internetAvailable) "AVAILABLE" else "OFFLINE", "informational; mesh messaging does not require internet")
                MetricLine("FORWARD QUEUE", mesh.pendingForwarding.toString(), "durable transit packets")
                MetricLine(
                    "WI-FI DIRECT GROUP",
                    if (mesh.wifiDirectGroupFormed) if (mesh.wifiDirectGroupOwner) "GROUP OWNER" else "GROUP CLIENT" else mesh.wifiDirectStage,
                    if (mesh.wifiDirectGroupOwner) "${mesh.wifiDirectGroupClientCount} Android group clients · ${mesh.connectedWifiPeers} Neyra sessions"
                    else "${mesh.connectedWifiPeers} Neyra sessions · Location Mode ${if (mesh.wifiDirectLocationModeEnabled) "on" else "off"}"
                )
            }
        }
        if (peers.isNotEmpty()) {
            item {
                SectionCard("PEERS · ONE IDENTITY PER NODE") {
                    peers.forEach { peer ->
                        val links = buildList {
                            if (!peer.bleAddress.isNullOrBlank()) add("BLE")
                            if (!peer.wifiHost.isNullOrBlank() && (peer.wifiPort ?: 0) > 0) add("Wi-Fi Direct")
                        }.joinToString(" · ").ifBlank { "No active link" }
                        MetricLine(peer.deviceName.ifBlank { peer.nodeId }, links, "${peer.nodeId} · protocol ${peer.meshProtocolVersion} · relay ${peer.canRelay} · store-forward ${peer.canStoreForward}")
                    }
                }
            }
        }
        if (mesh.wifiDirectEnabled && mesh.wifiDirectCandidates.isNotEmpty()) {
            item {
                SectionCard("WI-FI DIRECT DISCOVERY CANDIDATES") {
                    mesh.wifiDirectCandidates.forEach { candidate ->
                        MetricLine(candidate.deviceName, if (candidate.attemptInProgress) "IN PROGRESS · ${candidate.decision}" else candidate.decision, "version ${candidate.protocolVersion ?: "pending"} · ${java.text.DateFormat.getTimeInstance().format(java.util.Date(candidate.lastSeen))}")
                    }
                }
            }
        }
        if (currentRuntimeError || startupBlocked || failures > 0) {
            item {
                SectionCard(if (currentRuntimeError || startupBlocked) "CURRENT BLOCKERS / RUNTIME ISSUES" else "RECENT FAILED ATTEMPTS") {
                    if (mesh.bleEnabled && state.bleSupported && !state.blePermissions.allGranted) {
                        MetricLine("BLE PERMISSIONS", "INCOMPLETE", blePermissionSummary(state))
                    }
                    if (mesh.bleEnabled && state.bluetoothState != BluetoothState.ON) {
                        MetricLine("BLUETOOTH RADIO", bluetoothLabel(state.bluetoothState), "Turn Bluetooth on for BLE discovery and connections.")
                    }
                    if (mesh.wifiDirectEnabled && !wifiRadioEnabled) {
                        MetricLine("WI-FI RADIO", "OFF", "Turn Wi-Fi on for Wi-Fi Direct discovery and sessions.")
                    }
                    if (mesh.wifiDirectEnabled && !mesh.wifiDirectLocationModeEnabled) {
                        MetricLine("LOCATION MODE", "OFF", "Android may suppress Wi-Fi Direct service discovery while Location is off.")
                    }
                    if (mesh.bleEnabled && (state.bleRuntimeState == com.hybridmesh.relay.network.BleRuntimeState.DEGRADED || state.advertisingErrorCode != null || state.scanningErrorCode != null)) {
                        MetricLine("BLE", state.bleRuntimeState.name, "${state.initializationState.name} · ${bleOperationIssueSummary(state)}")
                    }
                    if (wifiHasError) MetricLine("WI-FI DIRECT", "ERROR", mesh.wifiDirectLastError ?: "Runtime is degraded; open Activity for the latest connection stage.")
                    if (failures > 0) MetricLine("FAILED / UNAVAILABLE", failures.toString(), "Recent attempts; one failed route does not mean the whole mesh is down.")
                }
            }
        }
        item {
            Text(
                "Updated ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())}. Values marked measured come from real transport attempts; — means no sample exists.",
                color = RelayTextMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun Activity(diagnostics: NetworkDiagnosticsSnapshot) {
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var selected by remember { mutableStateOf<NetworkDiagnosticEvent?>(null) }
    val filters = listOf("ALL", "FAILURES", "SLOW", "ACTIVE", "RETRIES")
    val activeEventIds = activeRequestEvents(diagnostics.events).mapTo(HashSet()) { it.id }
    val events = diagnostics.events.asReversed().filter {
        when (filter) {
            "FAILURES" -> it.kind == NetworkEventKind.REQUEST_FAILED ||
                it.kind == NetworkEventKind.REQUEST_UNAVAILABLE ||
                it.kind == NetworkEventKind.HEALTH_PROBE_FAILED
            "SLOW" -> (it.durationMs ?: 0L) >= 1_000L
            "ACTIVE" -> it.id in activeEventIds
            "RETRIES" -> (it.attempt ?: 1) > 1
            else -> true
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                filters.forEach { value ->
                    TextButton(onClick = { filter = value }) {
                        Text(value, color = if (filter == value) RelayAccent else RelayTextMuted)
                    }
                }
            }
            Text("${diagnostics.events.size} retained process-local events · ${diagnostics.droppedEvents} older events dropped", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        }
        items(events, key = { it.id }) { event ->
            EventRow(event, active = event.id in activeEventIds, onClick = { selected = event })
        }
        if (events.isEmpty()) {
            item { Text("No events match this filter.", color = RelayTextMuted, modifier = Modifier.padding(vertical = 20.dp)) }
        }
    }
    selected?.let { event ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("REQUEST DETAIL") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailLine("OPERATION", event.operation, event.kind.name)
                    DetailLine(
                        "TIME",
                        java.text.DateFormat.getTimeInstance().format(java.util.Date(event.timestamp)),
                        event.transport ?: "transport unavailable"
                    )
                    DetailLine("PEER", event.peerNodeId ?: "—", "attempt ${event.attempt ?: 1}")
                    DetailLine(
                        "DURATION",
                        event.durationMs?.let { "$it ms" } ?: "—",
                        "bytes ${formatBytes(event.bytes)}"
                    )
                    DetailLine("RESULT", event.result ?: "—", "event ${event.id}")
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("CLOSE") } }
        )
    }
}

@Composable
private fun Advanced(
    state: NetworkState,
    transport: com.hybridmesh.relay.messaging.model.GattTransportSnapshot,
    mesh: MeshRuntimeSnapshot,
    diagnostics: NetworkDiagnosticsSnapshot,
    wifiRadioEnabled: Boolean,
    wifiPermissionGranted: Boolean,
    wifiPermissionName: String,
    peers: List<MeshPeer>,
    attachments: List<AttachmentRecordEntity>
) {
    var showDiagnosis by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard("ADVANCED DIAGNOSTICS") {
                Text(
                    "Inspect live radio readiness, peer links, transfer state, relay queues, and recent connection events. Diagnostic events contain metadata only.",
                    color = RelayTextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = { showDiagnosis = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("DIAGNOSE NETWORK")
                }
                MetricLine(
                    "CURRENT MESH",
                    when {
                        !mesh.running -> "STOPPED"
                        mesh.availableTransports.isNotEmpty() -> "ACTIVE"
                        else -> "WAITING"
                    },
                    "${peers.size} unified peers · ${mesh.availableTransports.ifEmpty { listOf("no available carrier") }.joinToString(" · ")}"
                )
                val issueCount = diagnosticIssueCount(state, mesh, wifiRadioEnabled, wifiPermissionGranted)
                MetricLine("CHECKS NEEDING ATTENTION", issueCount.toString(), if (issueCount == 0) "No known radio or runtime blockers" else "Some checks may share a cause; Diagnose Network shows details")
                val relayEvents = diagnostics.events.filter { it.operation == "mesh.relay" }
                val relayFailures = relayEvents.count { it.kind == NetworkEventKind.REQUEST_FAILED }
                MetricLine(
                    "RELAY PIPELINE",
                    when {
                        !mesh.running -> "STOPPED"
                        relayFailures > 0 -> "CHECK EVENTS"
                        else -> "MONITORED"
                    },
                    "${mesh.pendingForwarding} queued · ${relayEvents.size} retained decisions · $relayFailures failures"
                )
            }
        }
    }
    if (showDiagnosis) {
        DiagnoseNetworkDialog(
            onDismiss = { showDiagnosis = false },
            state = state,
            transport = transport,
            mesh = mesh,
            diagnostics = diagnostics,
            wifiRadioEnabled = wifiRadioEnabled,
            wifiPermissionGranted = wifiPermissionGranted,
            wifiPermissionName = wifiPermissionName,
            peers = peers,
            attachments = attachments
        )
    }
}

@Composable
private fun DiagnoseNetworkDialog(
    onDismiss: () -> Unit,
    state: NetworkState,
    transport: com.hybridmesh.relay.messaging.model.GattTransportSnapshot,
    mesh: MeshRuntimeSnapshot,
    diagnostics: NetworkDiagnosticsSnapshot,
    wifiRadioEnabled: Boolean,
    wifiPermissionGranted: Boolean,
    wifiPermissionName: String,
    peers: List<MeshPeer>,
    attachments: List<AttachmentRecordEntity>
) {
    var selectedEvent by remember { mutableStateOf<NetworkDiagnosticEvent?>(null) }
    val recentEvents = diagnostics.events.asReversed().take(50)
    val activeEventIds = activeRequestEvents(diagnostics.events).mapTo(HashSet()) { it.id }
    val completedRequests = diagnostics.events.count {
        it.operation == "mesh.send" && isCompletedSendAttempt(it)
    }
    val relayEvents = diagnostics.events.filter { it.operation == "mesh.relay" }
    val recentRelayEvents = relayEvents.asReversed().take(30)
    val relayFailures = relayEvents.count { it.kind == NetworkEventKind.REQUEST_FAILED }
    val relayUnavailable = relayEvents.count { it.kind == NetworkEventKind.REQUEST_UNAVAILABLE }
    val bleBlocked = mesh.bleEnabled && (
        !state.bleSupported || !state.blePermissions.allGranted || state.bluetoothState != BluetoothState.ON ||
            state.scanningErrorCode != null || state.advertisingErrorCode != null ||
            state.scanningState == com.hybridmesh.relay.ble.BleOperationState.ERROR ||
            state.advertisingState == com.hybridmesh.relay.ble.BleOperationState.ERROR ||
            state.bleRuntimeState == com.hybridmesh.relay.network.BleRuntimeState.DEGRADED
        )
    val wifiBlocked = mesh.wifiDirectEnabled && (
        !wifiRadioEnabled || !wifiPermissionGranted || !mesh.wifiDirectLocationModeEnabled || mesh.wifiDirectLastError != null ||
            mesh.wifiDirectStage == "DEGRADED"
        )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(RelayBackground)) {
            Row(
                Modifier.fillMaxWidth().background(RelaySurface).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDismiss) { Text("← BACK") }
                Column(Modifier.weight(1f)) {
                    Text("DIAGNOSE NETWORK", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Live diagnostics · payload content is never shown", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SectionCard("SUMMARY & LIKELY BLOCKERS") {
                        MetricLine("MESH", if (mesh.running) "RUNNING" else "STOPPED", "BLE ${if (mesh.bleEnabled) "enabled" else "disabled"} · Wi-Fi Direct ${if (mesh.wifiDirectEnabled) "enabled" else "disabled"}")
                        MetricLine("AVAILABLE CARRIERS", mesh.availableTransports.ifEmpty { listOf("NONE") }.joinToString(" · "), "Carriers currently available for next-hop delivery")
                        if (mesh.bleEnabled && (!state.bleSupported || !state.blePermissions.allGranted || state.bluetoothState != BluetoothState.ON)) {
                            MetricLine("BLE BLOCKER", "CHECK REQUIRED", when {
                                !state.bleSupported -> "This device does not report BLE support."
                                !state.blePermissions.allGranted -> blePermissionSummary(state)
                                else -> "Bluetooth is ${bluetoothLabel(state.bluetoothState).lowercase(Locale.US)}."
                            })
                        }
                        if (mesh.wifiDirectEnabled && (!wifiRadioEnabled || !wifiPermissionGranted || !mesh.wifiDirectLocationModeEnabled)) {
                            MetricLine("WI-FI DIRECT BLOCKER", "CHECK REQUIRED", buildList {
                                if (!wifiRadioEnabled) add("Turn Wi-Fi on")
                                if (!wifiPermissionGranted) add("Grant $wifiPermissionName")
                                if (!mesh.wifiDirectLocationModeEnabled) add("Turn Location Mode on for service discovery")
                            }.joinToString(" · "))
                        }
                        if (bleBlocked && state.scanningErrorCode != null) MetricLine("BLE SCAN ERROR", "ERROR", bleErrorDetail(state.scanningErrorCode, BleErrorSource.SCAN))
                        if (bleBlocked && state.advertisingErrorCode != null) MetricLine("BLE ADVERTISE ERROR", "ERROR", bleErrorDetail(state.advertisingErrorCode, BleErrorSource.ADVERTISE))
                        if (bleBlocked && state.scanningState == com.hybridmesh.relay.ble.BleOperationState.ERROR && state.scanningErrorCode == null) {
                            MetricLine("BLE SCAN", "ERROR", "Android reported a scan failure without a platform error code.")
                        }
                        if (bleBlocked && state.advertisingState == com.hybridmesh.relay.ble.BleOperationState.ERROR && state.advertisingErrorCode == null) {
                            MetricLine("BLE ADVERTISING", "ERROR", "Android reported an advertising failure without a platform error code.")
                        }
                        if (mesh.wifiDirectEnabled && mesh.wifiDirectLastError != null) MetricLine("WI-FI DIRECT ERROR", "ERROR", mesh.wifiDirectLastError)
                        if (mesh.wifiDirectEnabled && mesh.wifiDirectStage == "DEGRADED" && mesh.wifiDirectLastError == null) {
                            MetricLine("WI-FI DIRECT", "DEGRADED", "No current error detail; inspect the latest Wi-Fi Direct events below.")
                        }
                        when {
                            !mesh.running -> MetricLine("STATUS", "MESH STOPPED", "Start the mesh service to enable transport diagnostics.")
                            !mesh.bleEnabled && !mesh.wifiDirectEnabled -> MetricLine("STATUS", "NO CARRIERS ENABLED", "Enable BLE and/or Wi-Fi Direct in Profile.")
                            !bleBlocked && !wifiBlocked -> MetricLine("STATUS", "NO CURRENT BLOCKERS", "Review recent events if a specific peer or transfer is still failing.")
                        }
                    }
                }
                item {
                    SectionCard("DEVICE & BLE RUNTIME") {
                        MetricLine("APP NETWORK", if (state.networkEnabled) "ENABLED" else "DISABLED", "Discovery requested ${state.discoveryRequested} · runtime ${state.bleRuntimeState}")
                        MetricLine("BLUETOOTH", bluetoothLabel(state.bluetoothState), "BLE support ${if (state.bleSupported) "available" else "unsupported"}")
                        MetricLine("BLE PERMISSIONS", if (state.blePermissions.allGranted) "GRANTED" else "INCOMPLETE", "${blePermissionSummary(state)} · aggregate ${state.permissionsGranted}")
                        MetricLine("INITIALIZATION", state.initializationState.name, "runtime generation ${state.runtimeGeneration}")
                        MetricLine("GATT SERVER", if (state.gattServerReady) "READY" else "NOT READY", "Local BLE GATT service")
                        MetricLine("SCANNING", state.scanningState.name, bleErrorDetail(state.scanningErrorCode, BleErrorSource.SCAN))
                        MetricLine("ADVERTISING", state.advertisingState.name, bleErrorDetail(state.advertisingErrorCode, BleErrorSource.ADVERTISE))
                        MetricLine("VALID SCAN RESULTS", state.scanResultCount.toString(), "last valid peer ${state.lastScanResultAt?.let { java.text.DateFormat.getTimeInstance().format(java.util.Date(it)) } ?: "—"}")
                        MetricLine("RAW SCAN CALLBACKS", state.rawScanResultCount.toString(), "last callback ${state.lastRawScanResultAt?.let { java.text.DateFormat.getTimeInstance().format(java.util.Date(it)) } ?: "—"}")
                        MetricLine("SCAN RECOVERIES", state.scanRecoveryCount.toString(), "last restart ${state.lastScanRecoveryAt?.let { java.text.DateFormat.getTimeInstance().format(java.util.Date(it)) } ?: "—"}")
                        if (state.peers.isEmpty()) Text("No live BLE peers are currently discovered.", color = RelayTextMuted)
                        if (state.peers.isNotEmpty()) DiagnosticListScroller(state.peers.size) {
                            state.peers.forEach { peer ->
                                MetricLine(peer.deviceName, peer.nodeId, "${peer.address} · ${peer.rssi} dBm · protocol ${peer.meshProtocolVersion} · relay ${peer.canRelay} · store-forward ${peer.canStoreForward}")
                            }
                        }
                    }
                }
                item {
                    SectionCard("WI-FI DIRECT RUNTIME") {
                        MetricLine("WI-FI RADIO", if (wifiRadioEnabled) "ON" else "OFF", "Location Mode ${if (mesh.wifiDirectLocationModeEnabled) "on" else "off"}")
                        MetricLine("WI-FI PERMISSION", if (wifiPermissionGranted) "GRANTED" else "MISSING", wifiPermissionName)
                        MetricLine("DISCOVERY", if (mesh.wifiDirectDiscoveryActive) "ACTIVE" else "INACTIVE", "candidate count ${mesh.wifiDirectCandidates.size}")
                        MetricLine("CONNECTION STAGE", displayStage(mesh.wifiDirectStage), "${mesh.wifiDirectRetryCount} discovery retries · stage age ${ageLabel(mesh.wifiDirectStageSince)}")
                        MetricLine("GROUP", if (mesh.wifiDirectGroupFormed) if (mesh.wifiDirectGroupOwner) "OWNER" else "CLIENT" else "NOT FORMED", "${mesh.wifiDirectGroupClientCount} Android clients · ${mesh.connectedWifiPeers} Neyra sessions")
                        MetricLine("GROUP OWNER ADDRESS", mesh.wifiDirectGroupOwnerAddress.takeIf { mesh.wifiDirectGroupFormed } ?: "—", "active group owner address")
                        MetricLine("LAST PEER", mesh.wifiDirectCurrentPeer ?: "—", "most recently targeted Wi-Fi Direct peer")
                        MetricLine("LATEST ATTEMPT ID", mesh.wifiDirectCurrentAttemptId ?: "—", "last Wi-Fi Direct connection attempt")
                        val controlServerStatus = when {
                            !mesh.wifiDirectGroupFormed -> "NOT RUNNING"
                            !mesh.wifiDirectGroupOwner -> "NOT REQUIRED"
                            mesh.wifiDirectControlServerReady -> "READY"
                            else -> "NOT READY"
                        }
                        MetricLine("CONTROL SERVER", controlServerStatus, "Group-owner control listener")
                        MetricLine("MEDIA SERVER", if (mesh.wifiDirectMediaServerReady) "READY" else "NOT READY", "Wi-Fi Direct attachment channel")
                        if (mesh.wifiDirectLastError != null) MetricLine("LAST ERROR", "ERROR", mesh.wifiDirectLastError)
                        if (mesh.wifiDirectStage == "DEGRADED" && mesh.wifiDirectLastError == null) {
                            MetricLine("LAST ERROR", "UNKNOWN", "No error detail was retained; inspect Recent Network Events.")
                        }
                        if (mesh.wifiDirectCandidates.isNotEmpty()) {
                            Text("DISCOVERED CANDIDATES", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            DiagnosticListScroller(mesh.wifiDirectCandidates.size) {
                                mesh.wifiDirectCandidates.asReversed().forEach { candidate ->
                                    MetricLine(candidate.deviceName, if (candidate.attemptInProgress) "IN PROGRESS · ${candidate.decision}" else candidate.decision, "${candidate.deviceAddress} · protocol ${candidate.protocolVersion ?: "pending"} · seen ${ageLabel(candidate.lastSeen)} ago")
                                }
                            }
                        }
                        if (mesh.wifiDirectPeers.isNotEmpty()) {
                            Text("PEER SESSION HEALTH", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            DiagnosticListScroller(mesh.wifiDirectPeers.size) {
                                mesh.wifiDirectPeers.forEach { peer ->
                                    val duration = peer.latestAttemptDurationMs?.let { " · last attempt ${it} ms" }.orEmpty()
                                    val failure = peer.lastFailureReason?.let { " · last failure $it" }.orEmpty()
                                    MetricLine(
                                        peer.deviceName.ifBlank { peer.nodeId },
                                        "${peer.groupRole} · ${peer.connectionStage}",
                                        "${peer.nodeId} · TCP ${if (peer.tcpSessionHealthy) "healthy" else "unavailable"} · route ${if (peer.routeEligible) "eligible" else "not eligible"}$duration$failure"
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    SectionCard("UNIFIED MESH PEERS") {
                        MetricLine("UNIQUE PEERS", peers.size.toString(), "One identity can expose one or both radio links")
                        if (peers.isEmpty()) Text("No unified peers are currently known.", color = RelayTextMuted)
                        if (peers.isNotEmpty()) DiagnosticListScroller(peers.size) {
                            peers.forEach { peer ->
                                val links = buildList {
                                    if (!peer.bleAddress.isNullOrBlank()) add("BLE ${peer.bleAddress} · ${peer.rssi} dBm")
                                    if (!peer.wifiHost.isNullOrBlank() && (peer.wifiPort ?: 0) > 0) add("Wi-Fi ${peer.wifiHost}:${peer.wifiPort}")
                                }.ifEmpty { listOf("No active endpoint") }.joinToString(" · ")
                                MetricLine(peer.deviceName.ifBlank { peer.nodeId }, peer.nodeId, "$links · BLE/Wi-Fi protocol ${peer.bleProtocolVersion}/${peer.wifiProtocolVersion} · relay ${peer.canRelay} · store-forward ${peer.canStoreForward}")
                            }
                        }
                    }
                }
                item {
                    SectionCard("ACTIVE BLE GATT TRANSFER") {
                        MetricLine("STATE", transport.state.name, "result ${transport.error.name}")
                        MetricLine("PEER", transport.peerAddress ?: "—", "message ${transport.messageId ?: "—"}")
                        val mtu = transport.mtu
                        MetricLine("MTU", mtu?.toString() ?: "—", mtu?.let { "${com.hybridmesh.relay.messaging.protocol.GattPacketCodec.safePayloadBytes(it)} B payload/frame" } ?: "not negotiated")
                        MetricLine("FRAME PROGRESS", "${transport.frameIndex?.plus(1) ?: 0} / ${transport.frameCount ?: 0}", "bytes sent ${formatBytes(transport.bytesSent.toLong())}")
                        MetricLine("UPDATED", if (transport.updatedAt > 0) java.text.DateFormat.getTimeInstance().format(java.util.Date(transport.updatedAt)) else "—", "active transport snapshot time")
                    }
                }
                item {
                    SectionCard("MESSAGING, RELAY & ATTACHMENTS") {
                        MetricLine("RELAY SERVICE", if (mesh.running) "RUNNING" else "STOPPED", "relay enabled ${mesh.relayEnabled}")
                        MetricLine("FORWARD QUEUE", mesh.pendingForwarding.toString(), "durable packets waiting for eligible routes")
                        MetricLine("SEEN CACHE", mesh.cachedPackets.toString(), "active duplicate-suppression entries")
                        MetricLine("LAST DELIVERY", mesh.lastDeliveredHopCount?.let { "$it hops" } ?: "—", "destination-confirmed hop count")
                        MetricLine("LAST QUEUE DELAY", mesh.lastForwardQueueDelayMs?.let { "$it ms" } ?: "—", "receive-to-send start")
                        MetricLine("LAST SEND", mesh.lastTransportSendDurationMs?.let { "$it ms" } ?: "—", "fan-out to first peer acceptance")
                        MetricLine("ATTACHMENT TRANSFER", if (mesh.attachmentTransferActive) "ACTIVE" else "IDLE", "transport ${mesh.attachmentTransport ?: "—"} · rate ${mesh.currentAttachmentThroughputMbps?.let { "${it.twoDecimal()} Mbps" } ?: mesh.lastAttachmentThroughputMbps?.let { "last ${it.twoDecimal()} Mbps" } ?: "—"}")
                        val recentAttachments = attachments.sortedByDescending { it.updatedAt }.take(12)
                        if (recentAttachments.isEmpty()) Text("No attachment transfer records.", color = RelayTextMuted)
                        if (recentAttachments.isNotEmpty()) DiagnosticListScroller(recentAttachments.size) {
                            recentAttachments.forEach { attachment ->
                                val progress = if (attachment.sizeBytes > 0L) (attachment.transferredBytes * 100 / attachment.sizeBytes).coerceIn(0L, 100L) else 0L
                                MetricLine(attachment.displayName, "${attachment.status} · $progress%", "${formatBytes(attachment.transferredBytes)} / ${formatBytes(attachment.sizeBytes)} · hops ${attachment.hopCount} · ${attachment.lastError ?: "no recorded error"}")
                            }
                        }
                    }
                }
                item {
                    SectionCard("RECENT NETWORK EVENTS") {
                        MetricLine("RETAINED", diagnostics.events.size.toString(), "${diagnostics.droppedEvents} older process-local events dropped")
                        MetricLine("TERMINAL SEND ATTEMPTS", completedRequests.toString(), "mesh sends that succeeded, failed, or had no available carrier")
                        MetricLine("EVENTS SHOWN", recentEvents.size.toString(), "latest events · select one for full correlation and result details")
                        if (recentEvents.isEmpty()) Text("No network events have been recorded in this process yet.", color = RelayTextMuted)
                        if (recentEvents.isNotEmpty()) DiagnosticListScroller(recentEvents.size) {
                            recentEvents.forEach { event ->
                                EventRow(event, active = event.id in activeEventIds) { selectedEvent = event }
                            }
                        }
                    }
                }
                item {
                    SectionCard("RELAY PIPELINE") {
                        Text(
                            "Relay decisions are recorded separately from physical transport sends. This distinguishes queueing, missing next hops, send failures, and worker exceptions.",
                            color = RelayTextMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        MetricLine("RELAY EVENTS", relayEvents.size.toString(), "retained process-local relay decisions")
                        MetricLine("RELAY FAILURES", relayFailures.toString(), "unexpected processing or transport failures")
                        MetricLine("NO ELIGIBLE PEER", relayUnavailable.toString(), "relay attempts waiting for a usable next hop")
                        MetricLine("PENDING FORWARDING", mesh.pendingForwarding.toString(), "durable relay records still awaiting progress")
                        if (recentRelayEvents.isEmpty()) {
                            Text("No relay decisions have been recorded in this process yet.", color = RelayTextMuted)
                        } else {
                            DiagnosticListScroller(recentRelayEvents.size) {
                                recentRelayEvents.forEach { event ->
                                    EventRow(event, active = false) { selectedEvent = event }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Diagnostic metadata is process-local and bounded. Message bodies, attachment bytes, and file paths are not displayed.",
                        color = RelayTextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
        }
    }
    selectedEvent?.let { event -> NetworkEventDetailsDialog(event) { selectedEvent = null } }
}

private fun diagnosticIssueCount(state: NetworkState, mesh: MeshRuntimeSnapshot, wifiRadioEnabled: Boolean, wifiPermissionGranted: Boolean): Int {
    var count = 0
    if (!mesh.running) count++
    if (!mesh.bleEnabled && !mesh.wifiDirectEnabled) count++
    if (mesh.bleEnabled) {
        if (!state.bleSupported || !state.blePermissions.allGranted || state.bluetoothState != BluetoothState.ON) count++
        if (state.scanningErrorCode != null || state.scanningState == com.hybridmesh.relay.ble.BleOperationState.ERROR) count++
        if (state.advertisingErrorCode != null || state.advertisingState == com.hybridmesh.relay.ble.BleOperationState.ERROR) count++
        if (state.bleRuntimeState == com.hybridmesh.relay.network.BleRuntimeState.DEGRADED) count++
    }
    if (mesh.wifiDirectEnabled) {
        if (!wifiRadioEnabled) count++
        if (!wifiPermissionGranted) count++
        if (!mesh.wifiDirectLocationModeEnabled) count++
        if (mesh.wifiDirectLastError != null || mesh.wifiDirectStage == "DEGRADED") count++
    }
    return count
}

private fun isCompletedSendAttempt(event: NetworkDiagnosticEvent): Boolean =
    event.kind == NetworkEventKind.REQUEST_SUCCEEDED ||
        event.kind == NetworkEventKind.REQUEST_FAILED ||
        event.kind == NetworkEventKind.REQUEST_UNAVAILABLE

private fun ageLabel(timestamp: Long): String {
    if (timestamp <= 0L) return "unknown"
    val elapsedSeconds = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L)) / 1_000L
    return when {
        elapsedSeconds < 60L -> "${elapsedSeconds}s"
        elapsedSeconds < 3_600L -> "${elapsedSeconds / 60L}m"
        else -> "${elapsedSeconds / 3_600L}h"
    }
}

@Composable
private fun rememberWifiDirectPermissionGranted(): Boolean {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember(context) { mutableStateOf(PermissionManager.wifiDirectPermissionGranted(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val refresh = { granted = PermissionManager.wifiDirectPermissionGranted(context) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

@Composable
private fun NetworkEventDetailsDialog(event: NetworkDiagnosticEvent, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NETWORK EVENT DETAIL") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailLine("OPERATION", event.operation, event.kind.name)
                DetailLine("TIME", java.text.DateFormat.getTimeInstance().format(java.util.Date(event.timestamp)), event.transport ?: "transport unavailable")
                DetailLine("PEER", event.peerNodeId ?: "—", "attempt ${event.attempt ?: 1}")
                DetailLine("CORRELATION", event.correlationId ?: "—", "event ${event.id}")
                DetailLine("DURATION & BYTES", event.durationMs?.let { "$it ms" } ?: "—", formatBytes(event.bytes))
                DetailLine("RESULT", event.result ?: "—", "payload data is not recorded")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

private enum class BleErrorSource { SCAN, ADVERTISE }

private fun bleErrorDetail(code: Int?, source: BleErrorSource): String {
    if (code == null) return "No current error"
    val explanation = when (source) {
        BleErrorSource.SCAN -> when (code) {
            -200 -> "App lacks a required Bluetooth permission"
            -201 -> "Bluetooth is unavailable on this device"
            -202 -> "Bluetooth is turned off"
            -203 -> "Android did not provide a BLE scanner"
            -204 -> "The scan configuration was rejected"
            1 -> "A scan is already active"
            2 -> "Android could not register this scan"
            3 -> "Android reported an internal scan error"
            4 -> "BLE scanning is unsupported"
            5 -> "The device has no scan resources available"
            6 -> "Scanning was started too frequently"
            else -> "Android BLE scan error"
        }
        BleErrorSource.ADVERTISE -> when (code) {
            -100 -> "App lacks a required Bluetooth permission"
            -101 -> "Bluetooth is unavailable on this device"
            -102 -> "Bluetooth is turned off"
            -104 -> "BLE advertising is unsupported"
            -105 -> "Android did not provide a BLE advertiser"
            -106 -> "The advertising configuration was rejected"
            1 -> "The advertising packet is too large"
            2 -> "No advertising slots are available"
            3 -> "This advertiser was already started"
            4 -> "Android reported an internal advertising error"
            5 -> "BLE advertising is unsupported"
            else -> "Android BLE advertising error"
        }
    }
    return "$explanation · code $code"
}

private fun blePermissionSummary(state: NetworkState): String = buildList {
    if (!state.blePermissions.scanGranted) add("scan")
    if (!state.blePermissions.advertiseGranted) add("advertise")
    if (!state.blePermissions.connectGranted) add("connect")
}.let { missing -> if (missing.isEmpty()) "Scan, advertise, and connect granted" else "Missing: ${missing.joinToString(", ")}" }

private fun bleOperationIssueSummary(state: NetworkState): String = buildList {
    state.scanningErrorCode?.let { add("scan ${bleErrorDetail(it, BleErrorSource.SCAN)}") }
    state.advertisingErrorCode?.let { add("advertising ${bleErrorDetail(it, BleErrorSource.ADVERTISE)}") }
}.ifEmpty { listOf("Check permissions, Bluetooth, and Activity events") }.joinToString(" · ")

@Composable
private fun EventRow(event: NetworkDiagnosticEvent, active: Boolean, onClick: () -> Unit) {
    val status = when (event.kind) {
        NetworkEventKind.REQUEST_SUCCEEDED -> "OK"
        NetworkEventKind.REQUEST_FAILED, NetworkEventKind.REQUEST_UNAVAILABLE, NetworkEventKind.HEALTH_PROBE_FAILED -> "ERROR"
        NetworkEventKind.REQUEST_STARTED -> if (active) "ACTIVE" else "STARTED"
        NetworkEventKind.CONNECTION_STAGE -> "STAGE"
        NetworkEventKind.DISCOVERY_CANDIDATE -> "FOUND"
        NetworkEventKind.TRANSPORT_AVAILABLE -> "READY"
        NetworkEventKind.TRANSPORT_UNAVAILABLE -> "LOST"
        NetworkEventKind.HEALTH_PROBE_SUCCEEDED -> "PROBE OK"
        NetworkEventKind.LIFECYCLE -> "STATE"
    }
    Row(
        Modifier.fillMaxWidth()
            .background(RelaySurface, MaterialTheme.shapes.large)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.widthIn(max = 220.dp)) {
            Text(event.operation, color = RelayAccent, style = TechnicalTextStyle)
            Text("${event.transport ?: "runtime"} · ${event.peerNodeId ?: "local"}", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
            event.result?.let { Text(it, color = RelayTextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(status, color = if (status == "ERROR" || status == "LOST") MaterialTheme.colorScheme.error else RelayAccent, style = TechnicalTextStyle)
            Text(event.durationMs?.let { "$it ms" } ?: "—", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(RelaySurface, MaterialTheme.shapes.large)
            .border(1.dp, RelayBorder, MaterialTheme.shapes.large).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = TechnicalTextStyle, color = RelayTextMuted, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun DiagnosticListScroller(itemCount: Int, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (itemCount > 6) {
            Text("Scroll inside to see all $itemCount entries", color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        }
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String, detail: String) {
    val needsAttention = value.uppercase(Locale.US) in setOf("ERROR", "INCOMPLETE", "MISSING", "OFF", "TURNING OFF", "UNSUPPORTED", "DEGRADED", "UNKNOWN", "NEEDS ATTENTION", "CHECK REQUIRED")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label, color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
            Text(detail, color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            value,
            color = if (needsAttention) MaterialTheme.colorScheme.error else RelayAccent,
            style = TechnicalTextStyle,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 170.dp).padding(start = 10.dp)
        )
    }
}

private fun displayStage(stage: String): String = stage.lowercase(Locale.US)
    .replace('_', ' ')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

@Composable
private fun DetailLine(label: String, value: String, detail: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
        Text(value, color = RelayAccent, style = TechnicalTextStyle)
        Text(detail, color = RelayTextMuted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "${(bytes / 1_024.0).oneDecimal()} KB"
    else -> "${(bytes / (1_024.0 * 1_024.0)).oneDecimal()} MB"
}

private fun Double.oneDecimal(): String = String.format(Locale.US, "%.1f", this)
private fun Double.twoDecimal(): String = String.format(Locale.US, "%.2f", this)

private data class DiagnosticAttemptKey(
    val attemptId: String?,
    val correlationId: String?,
    val transport: String?,
    val peerNodeId: String?,
    val attempt: Int?
)

private fun NetworkDiagnosticEvent.attemptKey() = DiagnosticAttemptKey(
    attemptId = attemptId,
    correlationId = correlationId,
    transport = transport,
    peerNodeId = peerNodeId,
    attempt = attempt
)

private fun activeRequestEvents(events: List<NetworkDiagnosticEvent>): List<NetworkDiagnosticEvent> {
    val terminalRequests = events.filter { it.operation == "mesh.send" && isCompletedSendAttempt(it) }
    val terminalAttemptKeys = terminalRequests.mapTo(HashSet()) { it.attemptKey() }
    val activeRequests = events.filter {
        it.operation == "mesh.send" &&
            it.kind == NetworkEventKind.REQUEST_STARTED &&
            it.attemptKey() !in terminalAttemptKeys
    }

    val completedWifiSetups = events.asSequence()
        .filter {
            it.kind == NetworkEventKind.CONNECTION_STAGE &&
                it.operation in setOf("wifi.tcp.session_active", "wifi.p2p.session_ready", "wifi.p2p.stopped")
        }
        .mapNotNull { it.correlationId }
        .toSet()
    val failedWifiSetups = events.asSequence()
        .filter { it.kind == NetworkEventKind.REQUEST_FAILED && it.operation.startsWith("wifi.") }
        .mapNotNull { it.correlationId }
        .toSet()
    val latestWifiStages = events.asSequence()
        .filter {
            it.kind == NetworkEventKind.CONNECTION_STAGE &&
                it.transport.equals("WIFI_DIRECT", ignoreCase = true) &&
                it.correlationId != null &&
                it.correlationId !in completedWifiSetups &&
                it.correlationId !in failedWifiSetups
        }
        .groupBy { it.correlationId to it.transport }
        .values
        .mapNotNull { attemptEvents -> attemptEvents.maxByOrNull { it.timestamp } }
    return activeRequests + latestWifiStages
}

private fun bluetoothLabel(state: BluetoothState): String = when (state) {
    BluetoothState.ON -> "ON"
    BluetoothState.OFF -> "OFF"
    BluetoothState.TURNING_ON -> "TURNING ON"
    BluetoothState.TURNING_OFF -> "TURNING OFF"
    BluetoothState.UNSUPPORTED -> "UNSUPPORTED"
    BluetoothState.ERROR -> "ERROR"
}
