package com.hybridmesh.relay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import kotlin.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hybridmesh.relay.network.BluetoothState
import com.hybridmesh.relay.network.NetworkManager
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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HybridMeshRelayTheme {
                HybridMeshRelayApp()
            }
        }
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
    AppScreen.MESSAGES,
    AppScreen.NETWORK,
    AppScreen.DEVICES,
    AppScreen.PROFILE
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HybridMeshRelayApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val networkManager =
        NetworkManager.getInstance(context)

    val networkState by networkManager.state.collectAsState()

    var permissionRequested by rememberSaveable {
        mutableStateOf(false)
    }

    var bluetoothPromptRequested by rememberSaveable {
        mutableStateOf(false)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            networkManager.refresh()
        }

    val bluetoothLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            networkManager.refresh()
        }

    LaunchedEffect(
        networkState.permissionsGranted
    ) {
        if (
            !networkState.permissionsGranted &&
            !permissionRequested
        ) {
            permissionRequested = true

            permissionLauncher.launch(
                requiredBlePermissions()
            )
        }
    }

    /*
     * Start the long-lived mesh service only after the required
     * Bluetooth permissions are available.
     *
     * The service is intentionally independent of the current
     * Compose screen, so leaving Devices/Messages/etc. does not
     * shut down the mesh runtime.
     */
    LaunchedEffect(networkState.permissionsGranted) {
        if (networkState.permissionsGranted) {
            HybridMeshService.start(context)
        }
    }

    LaunchedEffect(
        networkState.permissionsGranted,
        networkState.bluetoothState
    ) {
        if (
            networkState.permissionsGranted &&
            networkState.bluetoothState ==
                BluetoothState.OFF &&
            !bluetoothPromptRequested
        ) {
            bluetoothPromptRequested = true

            bluetoothLauncher.launch(
                Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE
                )
            )
        }
    }

    val navigationStack =
        remember {
            mutableStateListOf(
                AppScreen.HOME
            )
        }

    var selectedPeerNodeId by remember {
        mutableStateOf<String?>(null)
    }

    val currentScreen =
        navigationStack.last()

    val isNestedScreen =
        currentScreen !in primaryScreens

    fun navigate(
        screen: AppScreen
    ) {
        navigationStack.add(screen)
    }

    fun navigateTab(
        screen: AppScreen
    ) {
        navigationStack.clear()
        navigationStack.add(screen)
    }

    fun goBack() {
        if (
            navigationStack.size > 1
        ) {
            navigationStack.removeAt(
                navigationStack.lastIndex
            )
        }
    }

    fun openConversation(
        nodeId: String
    ) {
        selectedPeerNodeId = nodeId
        navigate(
            AppScreen.CONVERSATION
        )
    }

    BackHandler(
        enabled = navigationStack.size > 1,
        onBack = ::goBack
    )

    Scaffold(
        contentWindowInsets =
            WindowInsets.safeDrawing,

        topBar = {
            if (isNestedScreen) {
                TopAppBar(
                    title = {
                        Text(
                            text = currentScreen.title,
                            maxLines = 1
                        )
                    },

                    navigationIcon = {
                        IconButton(
                            onClick = ::goBack
                        ) {
                            Text(
                                text = "‹",
                                style =
                                    MaterialTheme
                                        .typography
                                        .headlineSmall
                            )
                        }
                    }
                )
            }
        },

        bottomBar = {
            if (!isNestedScreen) {
                AppBottomBar(
                    currentScreen = currentScreen,
                    onNavigate = ::navigateTab
                )
            }
        }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme
                        .colorScheme
                        .background
                )
                .padding(
                    paddingValues
                )
        ) {

            if (!isNestedScreen) {
                AppStatusStrip()
            }

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                when (currentScreen) {

                    AppScreen.HOME -> {
                        HomeScreen(
                            onNetworkClick = {
                                navigateTab(AppScreen.NETWORK)
                            }
                        )
                    }

                    AppScreen.MESSAGES -> {
                        MessagesScreen(
                            onConversationClick =
                                ::openConversation,

                            onOpenDevices = {
                                navigateTab(
                                    AppScreen.DEVICES
                                )
                            }
                        )
                    }

                    AppScreen.NETWORK -> {
                        NetworkScreen()
                    }

                    AppScreen.DEVICES -> {
                        DevicesScreen()
                    }

                    AppScreen.CONVERSATION -> {
                        selectedPeerNodeId?.let { peerId ->
                            ConversationScreen(
                                peerNodeId = peerId
                            )
                        }
                    }

                    AppScreen.PROFILE -> {
                        ProfileScreen(
                            onBack = ::goBack
                        )
                    }
                }
            }
        }
    }
}

private fun requiredBlePermissions():
    Array<String> {
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
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}

private fun hasRequiredBlePermissions(
    context: Context
): Boolean {
    return requiredBlePermissions().all { permission ->
        androidx.core.content.ContextCompat
            .checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
    }
}

@Suppress("unused")
private fun isBluetoothEnabled(
    context: Context
): Boolean {
    return runCatching {
        context
            .getSystemService(
                BluetoothManager::class.java
            )
            ?.adapter
            ?.isEnabled == true
    }.getOrDefault(false)
}