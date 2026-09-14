package com.hybridmesh.relay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
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
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
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
    val identityStore = com.hybridmesh.relay.data.IdentityStore.getInstance(context)
    val networkState by networkManager.state.collectAsStateWithLifecycle()
    val identity by identityStore.identity.collectAsStateWithLifecycle()
    val peerRequest by openPeerRequest.collectAsStateWithLifecycle()

    var foregroundCycle by rememberSaveable { mutableIntStateOf(0) }
    var bleRequestedCycle by rememberSaveable { mutableIntStateOf(-1) }
    var notificationRequestedCycle by rememberSaveable { mutableIntStateOf(-1) }
    var showPermissionRecovery by remember { mutableStateOf(false) }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        networkManager.refresh()
        showPermissionRecovery = !PermissionManager.ble(context).allGranted
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
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
                networkManager.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(foregroundCycle, networkState.blePermissions) {
        val missing = PermissionManager.missingBlePermissions(context)
        if (missing.isNotEmpty() && bleRequestedCycle != foregroundCycle) {
            bleRequestedCycle = foregroundCycle
            blePermissionLauncher.launch(missing)
        }
    }

    LaunchedEffect(foregroundCycle, networkState.blePermissions.allGranted, identityStore.isNicknameConfigured()) {
        val notificationPermission = PermissionManager.notifications(context)
        val shouldPrompt = notificationPermission.level == com.hybridmesh.relay.permissions.NotificationPermissionLevel.NOT_REQUESTED ||
            (notificationPermission.level == com.hybridmesh.relay.permissions.NotificationPermissionLevel.DENIED && notificationPermission.previouslyGranted)
        if (identityStore.isNicknameConfigured() &&
            networkState.blePermissions.allGranted &&
            shouldPrompt &&
            notificationRequestedCycle != foregroundCycle
        ) {
            notificationRequestedCycle = foregroundCycle
            notificationPermissionLauncher.launch(PermissionManager.notificationPermissionsToRequest())
        }
    }

    LaunchedEffect(networkState.blePermissions.allGranted) {
        if (networkState.blePermissions.allGranted) {
            // The runtime may initialize while onboarding is visible.
            // Advertising itself remains gated until the nickname exists.
            runCatching { HybridMeshService.start(context) }
        }
    }

    LaunchedEffect(networkState.permissionsGranted, networkState.bluetoothState) {
        if (networkState.permissionsGranted && networkState.bluetoothState == BluetoothState.OFF) {
            bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    val navigationStack = remember { mutableStateListOf(AppScreen.HOME) }
    var selectedPeerNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMessagesTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(peerRequest) {
        val request = peerRequest?.trim().orEmpty()
        if (request.isNotBlank()) {
            selectedPeerNodeId = request
            navigationStack.clear()
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

    if (!identityStore.isNicknameConfigured()) {
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
                    AppScreen.PROFILE -> ProfileScreen()
                }
            }
        }
    }

    if (showPermissionRecovery) {
        AlertDialog(
            onDismissRequest = { showPermissionRecovery = false },
            title = { Text("Bluetooth access is still missing") },
            text = {
                Text("Neyra will check again the next time you open the app. You can also grant the missing Nearby Devices permissions from Android settings now.")
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
}

@Composable
private fun NicknameOnboarding(
    initialNickname: String,
    onSave: (String) -> Unit
) {
    var nickname by rememberSaveable { mutableStateOf(initialNickname) }
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
            onValueChange = { nickname = it.take(32) },
            label = { Text("Nickname") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(72.dp)
        )
        androidx.compose.material3.Button(
            onClick = { if (nickname.trim().isNotBlank()) onSave(nickname.trim()) },
            enabled = nickname.trim().isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 12.dp)
        ) { Text("CONTINUE") }
    }
}