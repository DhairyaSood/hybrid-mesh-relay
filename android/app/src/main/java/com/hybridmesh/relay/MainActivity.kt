package com.hybridmesh.relay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager as AndroidLocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.hybridmesh.relay.ui.theme.RelayAccent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridmesh.relay.data.NicknamePolicy
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
import com.hybridmesh.relay.network.MeshTransportPreferences
import com.hybridmesh.relay.permissions.PermissionManager
import com.hybridmesh.relay.service.HybridMeshService
import com.hybridmesh.relay.ui.ConversationScreen
import com.hybridmesh.relay.ui.DevicesScreen
import com.hybridmesh.relay.ui.HomeScreen
import com.hybridmesh.relay.ui.MessagesScreen
import com.hybridmesh.relay.ui.NetworkScreen
import com.hybridmesh.relay.ui.ProfileScreen
import com.hybridmesh.relay.ui.components.AppBottomBar
import com.hybridmesh.relay.ui.components.AppStatusStrip
import com.hybridmesh.relay.ui.theme.HybridMeshRelayTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openPeerRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPeerRequest.value = intent.getStringExtra(EXTRA_OPEN_PEER_NODE_ID)
        enableEdgeToEdge()
        setContent {
            HybridMeshRelayTheme { HybridMeshRelayApp(openPeerRequest) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPeerRequest.value = intent?.getStringExtra(EXTRA_OPEN_PEER_NODE_ID)
    }

    fun clearOpenPeerRequest() {
        openPeerRequest.value = null
    }

    companion object {
        const val EXTRA_OPEN_PEER_NODE_ID = "open_peer_node_id"
    }
}

enum class AppScreen(val title: String) {
    HOME("Home"),
    NETWORK("Network"),
    MESSAGES("Messages"),
    DEVICES("Devices"),
    CONVERSATION("Conversation"),
    PROFILE("Device Profile")
}

private val primaryScreens = setOf(
    AppScreen.HOME,
    AppScreen.NETWORK,
    AppScreen.MESSAGES,
    AppScreen.DEVICES,
    AppScreen.PROFILE
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HybridMeshRelayApp(openPeerRequest: MutableStateFlow<String?>) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val networkManager = NetworkManager.getInstance(context)
    val transportPreferences = MeshTransportPreferences.getInstance(context)
    val transportSelection by transportPreferences.selection.collectAsStateWithLifecycle()
    val identityStore = com.hybridmesh.relay.data.IdentityStore.getInstance(context)
    val networkState by networkManager.state.collectAsStateWithLifecycle()
    val identity by identityStore.identity.collectAsStateWithLifecycle()
    val peerRequest by openPeerRequest.collectAsStateWithLifecycle()

    var foregroundCycle by rememberSaveable { mutableIntStateOf(0) }
    var notificationRequestedCycle by rememberSaveable { mutableIntStateOf(-1) }
    var radioPromptShownForOffState by rememberSaveable { mutableStateOf(false) }
    var showRadioPrompt by rememberSaveable { mutableStateOf(false) }
    var showLocationModePrompt by remember { mutableStateOf(false) }
    var locationPromptShownForOffState by remember { mutableStateOf(false) }
    var previousWifiDirectEnabled by remember { mutableStateOf(transportSelection.wifiDirectEnabled) }
    var wifiDirectLocationModeEnabled by remember(context) {
        mutableStateOf(PermissionManager.wifiDirectLocationModeEnabled(context))
    }
    var meshPermissionRequestLaunched by remember { mutableStateOf(false) }
    var meshPermissionRequestInProgress by remember { mutableStateOf(false) }
    var notificationPermissionRequestInProgress by remember { mutableStateOf(false) }
    var showPermissionRecovery by remember { mutableStateOf(false) }
    val hasAnyMeshTransportPermission = PermissionManager.hasEnabledMeshTransportPermissionGranted(context, transportSelection)

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        meshPermissionRequestInProgress = false
        PermissionManager.markMeshPermissionRequestCompleted(context)
        networkManager.refresh()
        // A denied bearer must not block the other one. Show recovery only when
        // neither BLE nor Wi-Fi Direct has the permission needed to operate.
        showPermissionRecovery = transportSelection.anyEnabled &&
            !PermissionManager.hasEnabledMeshTransportPermissionGranted(context, transportSelection)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        notificationPermissionRequestInProgress = false
        PermissionManager.markNotificationRequestCompleted(context, result.values.any { it })
        networkManager.refresh()
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        networkManager.refresh()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foregroundCycle += 1
                wifiDirectLocationModeEnabled = PermissionManager.wifiDirectLocationModeEnabled(context)
                networkManager.onForegroundResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (intent?.action == AndroidLocationManager.MODE_CHANGED_ACTION) {
                    wifiDirectLocationModeEnabled = PermissionManager.wifiDirectLocationModeEnabled(appContext)
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(AndroidLocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { appContext.unregisterReceiver(receiver) }
    }

    LaunchedEffect(foregroundCycle, networkState.blePermissions, transportSelection, identity.deviceName) {
        if (identity.deviceName.isBlank()) return@LaunchedEffect
        val missing = PermissionManager.missingEnabledMeshPermissions(context, transportSelection)
        if (missing.isEmpty()) {
            PermissionManager.markMeshPermissionRequestCompleted(context)
        } else if (!PermissionManager.hasCompletedMeshPermissionRequest(context) && !meshPermissionRequestLaunched) {
            // The in-memory guard prevents duplicate launches during recomposition.
            // Completion is persisted only after Android returns a result, so a
            // process death while the dialog is open does not strand permissions.
            meshPermissionRequestLaunched = true
            meshPermissionRequestInProgress = true
            blePermissionLauncher.launch(missing)
        }
    }

    // If permissions are later revoked in Android Settings, surface recovery on
    // the next foreground visit instead of silently leaving the runtime stopped.
    LaunchedEffect(foregroundCycle, hasAnyMeshTransportPermission, transportSelection, identity.deviceName) {
        if (!transportSelection.anyEnabled) {
            showPermissionRecovery = false
            return@LaunchedEffect
        }
        if (identity.deviceName.isNotBlank() &&
            !hasAnyMeshTransportPermission &&
            PermissionManager.hasCompletedMeshPermissionRequest(context)
        ) {
            showPermissionRecovery = true
        }
    }

    LaunchedEffect(foregroundCycle, networkState.blePermissions.allGranted, hasAnyMeshTransportPermission, transportSelection, identity.deviceName) {
        if (identity.deviceName.isBlank()) return@LaunchedEffect
        val notificationPermission = PermissionManager.notifications(context)
        val shouldPrompt = notificationPermission.level == com.hybridmesh.relay.permissions.NotificationPermissionLevel.NOT_REQUESTED ||
            (notificationPermission.level == com.hybridmesh.relay.permissions.NotificationPermissionLevel.DENIED && notificationPermission.previouslyGranted)
        if (identity.deviceName.isNotBlank() &&
            hasAnyMeshTransportPermission &&
            shouldPrompt &&
            notificationRequestedCycle != foregroundCycle
        ) {
            notificationRequestedCycle = foregroundCycle
            notificationPermissionRequestInProgress = true
            notificationPermissionLauncher.launch(PermissionManager.notificationPermissionsToRequest())
        }
    }

    // Start the service if either bearer can operate. In particular, do not gate
    // Wi-Fi Direct behind the full BLE permission set.
    LaunchedEffect(foregroundCycle, networkState.blePermissions.allGranted, hasAnyMeshTransportPermission, transportSelection, identity.deviceName) {
        if (hasAnyMeshTransportPermission && identity.deviceName.isNotBlank()) {
            runCatching { HybridMeshService.start(context) }
        }
    }

    LaunchedEffect(
        foregroundCycle,
        networkState.bluetoothState,
        identity.deviceName
    ) {
        val wifiEnabled = runCatching {
            context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(false)
        if (identity.deviceName.isNotBlank()) {
            val bluetoothEnabled = networkState.bluetoothState == BluetoothState.ON
            if (wifiEnabled || bluetoothEnabled) {
                // Once either bearer is available, leave the other radio alone.
                radioPromptShownForOffState = false
                showRadioPrompt = false
            } else if (!radioPromptShownForOffState) {
                radioPromptShownForOffState = true
                showRadioPrompt = true
            }
        }
    }

    LaunchedEffect(
        foregroundCycle,
        transportSelection.wifiDirectEnabled,
        wifiDirectLocationModeEnabled,
        identity.deviceName,
        meshPermissionRequestInProgress,
        notificationPermissionRequestInProgress,
        showPermissionRecovery,
        showRadioPrompt
    ) {
        val wifiDirectJustEnabled = transportSelection.wifiDirectEnabled && !previousWifiDirectEnabled
        previousWifiDirectEnabled = transportSelection.wifiDirectEnabled

        if (!transportSelection.wifiDirectEnabled) {
            showLocationModePrompt = false
            locationPromptShownForOffState = false
            return@LaunchedEffect
        }
        if (wifiDirectLocationModeEnabled) {
            showLocationModePrompt = false
            locationPromptShownForOffState = false
            return@LaunchedEffect
        }
        // ProfileScreen sequences its permission request first and invokes the
        // same prompt callback after the user returns from that request.
        if (wifiDirectJustEnabled || identity.deviceName.isBlank() ||
            meshPermissionRequestInProgress || notificationPermissionRequestInProgress ||
            showPermissionRecovery || showRadioPrompt
        ) return@LaunchedEffect

        if (!locationPromptShownForOffState) {
            locationPromptShownForOffState = true
            showLocationModePrompt = true
        }
    }

    val navigationStack = remember { mutableStateListOf(AppScreen.HOME) }
    var selectedPeerNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMessagesTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(peerRequest) {
        val request = peerRequest?.trim().orEmpty()
        if (request.isNotBlank()) {
            selectedPeerNodeId = request
            selectedMessagesTab = 0
            navigationStack.clear()
            navigationStack.add(AppScreen.MESSAGES)
            navigationStack.add(AppScreen.CONVERSATION)
            activity?.clearOpenPeerRequest()
        }
    }

    val currentScreen = navigationStack.last()
    val isNestedScreen = currentScreen !in primaryScreens

    fun navigateTab(screen: AppScreen) {
        navigationStack.clear()
        navigationStack.add(screen)
    }

    fun goBack() {
        if (navigationStack.size > 1) navigationStack.removeAt(navigationStack.lastIndex)
    }

    fun openConversation(nodeId: String) {
        selectedPeerNodeId = nodeId
        navigationStack.add(AppScreen.CONVERSATION)
    }

    BackHandler(enabled = navigationStack.size > 1, onBack = ::goBack)

    if (identity.deviceName.isBlank()) {
        NicknameOnboarding(
            initialNickname = "",
            onSave = { nickname -> identityStore.updateDeviceName(nickname) }
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (isNestedScreen) {
                TopAppBar(
                    title = { Text(currentScreen.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = ::goBack) {
                            Text("‹", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!isNestedScreen) {
                AppBottomBar(currentScreen = currentScreen, onNavigate = ::navigateTab)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
        ) {
            if (!isNestedScreen) AppStatusStrip()
            Box(Modifier.fillMaxSize()) {
                when (currentScreen) {
                    AppScreen.HOME -> HomeScreen(onNetworkClick = { navigateTab(AppScreen.NETWORK) })
                    AppScreen.NETWORK -> NetworkScreen()
                    AppScreen.MESSAGES -> MessagesScreen(
                        selectedTab = selectedMessagesTab,
                        onSelectedTabChange = { selectedMessagesTab = it },
                        onConversationClick = ::openConversation,
                        onOpenDevices = { navigateTab(AppScreen.DEVICES) }
                    )
                    AppScreen.DEVICES -> DevicesScreen()
                    AppScreen.CONVERSATION -> selectedPeerNodeId?.let { ConversationScreen(peerNodeId = it) }
                    AppScreen.PROFILE -> ProfileScreen(
                        onLocationModeRequired = {
                            locationPromptShownForOffState = true
                            showLocationModePrompt = true
                        }
                    )
                }
            }
        }
    }

    if (showPermissionRecovery) {
        AlertDialog(
            onDismissRequest = { showPermissionRecovery = false },
            title = { Text("Nearby transport access is still missing") },
            text = {
                Text("Neyra needs permission for at least one nearby transport. Grant Bluetooth permissions for BLE, and/or the Wi-Fi Direct permission, in Android settings. Either transport can operate without the other.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRecovery = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("OPEN APP SETTINGS") }
            },
            dismissButton = { TextButton(onClick = { showPermissionRecovery = false }) { Text("LATER") } }
        )
    }

    if (showRadioPrompt) {
        AlertDialog(
            onDismissRequest = { showRadioPrompt = false },
            title = { Text("Turn on a nearby connection") },
            text = {
                Text("For the best discovery, turn on both Wi-Fi and Bluetooth. Wi-Fi Direct is Neyra's preferred connection, and Bluetooth provides BLE discovery and a fallback. Neyra will keep working if either radio is later turned off.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRadioPrompt = false
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }) { Text("WI-FI SETTINGS") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = {
                        showRadioPrompt = false
                        bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }) { Text("BLUETOOTH") }
                    TextButton(onClick = { showRadioPrompt = false }) { Text("LATER") }
                }
            }
        )
    }

    if (showLocationModePrompt && transportSelection.wifiDirectEnabled && !wifiDirectLocationModeEnabled) {
        AlertDialog(
            onDismissRequest = { showLocationModePrompt = false },
            title = { Text("Turn on Location for Wi-Fi Direct") },
            text = {
                Text("Android requires the device's Location setting to be on for Wi-Fi Direct discovery. This is separate from Neyra's runtime permission. Turn it on in Settings, then return to Neyra.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLocationModePrompt = false
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) { Text("LOCATION SETTINGS") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationModePrompt = false }) { Text("NOT NOW") }
            }
        )
    }
}

@Composable
private fun NicknameOnboarding(
    initialNickname: String,
    onSave: (String) -> Unit
) {
    var nickname by rememberSaveable { mutableStateOf(initialNickname) }
    val validationError = NicknamePolicy.errorMessage(nickname)
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Text("Neyra", style = MaterialTheme.typography.displaySmall, color = RelayAccent)
        Text("Choose your name", style = MaterialTheme.typography.headlineMedium, color = RelayAccent, modifier = Modifier.padding(top = 20.dp))
        Text(
            "This is how you'll appear to nearby devices. It can be anything you like and doesn't need to be unique.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        androidx.compose.material3.OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = NicknamePolicy.clean(it) },
            isError = nickname.isNotEmpty() && validationError != null,
            label = { Text("Username") },
            supportingText = {
                Text(validationError ?: "Up to ${NicknamePolicy.MAX_LENGTH} characters. Use letters, numbers, _ . - @ & \$ ! # ^ ~.")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.material3.Button(
            onClick = {
                val cleaned = NicknamePolicy.clean(nickname)
                if (NicknamePolicy.isValid(cleaned)) onSave(cleaned)
            },
            enabled = NicknamePolicy.isValid(nickname),
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 12.dp)
        ) { Text("CONTINUE") }
    }
}
