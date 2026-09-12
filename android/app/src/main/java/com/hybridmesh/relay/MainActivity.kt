package com.hybridmesh.relay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.hybridmesh.relay.ui.ComposeMessageScreen
import com.hybridmesh.relay.ui.DiagnosticsScreen
import com.hybridmesh.relay.ui.DevicesScreen
import com.hybridmesh.relay.ui.HomeScreen
import com.hybridmesh.relay.ui.MessageDetailScreen
import com.hybridmesh.relay.ui.MessagesScreen
import com.hybridmesh.relay.ui.NetworkScreen
import com.hybridmesh.relay.ui.components.AppBottomBar
import com.hybridmesh.relay.ui.components.AppStatusStrip
import com.hybridmesh.relay.ui.theme.HybridMeshRelayTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HybridMeshRelayTheme {
                HybridMeshRelayApp()
            }
        }
    }
}

enum class AppScreen(
    val title: String
) {
    HOME("Home"),
    MESSAGES("Messages"),
    NETWORK("Network"),
    DEVICES("Devices"),
    DIAGNOSTICS("Diagnostics"),
    COMPOSE("New Message"),
    MESSAGE_DETAIL("Conversation")
}

private val primaryScreens = setOf(
    AppScreen.HOME,
    AppScreen.MESSAGES,
    AppScreen.NETWORK,
    AppScreen.DEVICES,
    AppScreen.DIAGNOSTICS
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HybridMeshRelayApp() {

    val navigationStack = remember {
        mutableStateListOf(AppScreen.HOME)
    }

    val currentScreen = navigationStack.last()
    val isNestedScreen = currentScreen !in primaryScreens

    fun navigateTo(screen: AppScreen) {
        navigationStack.add(screen)
    }

    fun navigateToTab(screen: AppScreen) {
        navigationStack.clear()
        navigationStack.add(screen)
    }

    BackHandler(
        enabled = navigationStack.size > 1
    ) {
        navigationStack.removeAt(navigationStack.lastIndex)
    }

    Scaffold(
        topBar = {
            if (isNestedScreen) {
                TopAppBar(
                    title = {
                        Text(currentScreen.title)
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                navigationStack.removeAt(
                                    navigationStack.lastIndex
                                )
                            }
                        ) {
                            Text("‹")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!isNestedScreen) {
                AppBottomBar(
                    currentScreen = currentScreen,
                    onNavigate = ::navigateToTab
                )
            }
        }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.material3.MaterialTheme.colorScheme.background
                )
                .padding(paddingValues)
        ) {

            if (!isNestedScreen) {
                AppStatusStrip()
            }

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                AppScreenContent(
                    screen = currentScreen,
                    onNavigate = ::navigateTo,
                    onNavigateToTab = ::navigateToTab
                )
            }
        }
    }
}

@Composable
private fun AppScreenContent(
    screen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    onNavigateToTab: (AppScreen) -> Unit
) {
    when (screen) {

        AppScreen.HOME -> HomeScreen(
            onMessagesClick = {
                onNavigateToTab(AppScreen.MESSAGES)
            },
            onNetworkClick = {
                onNavigateToTab(AppScreen.NETWORK)
            },
            onDevicesClick = {
                onNavigateToTab(AppScreen.DEVICES)
            },
            onDiagnosticsClick = {
                onNavigateToTab(AppScreen.DIAGNOSTICS)
            },
            onNewMessage = {
                onNavigate(AppScreen.COMPOSE)
            },
            onMessageClick = {
                onNavigate(AppScreen.MESSAGE_DETAIL)
            }
        )

        AppScreen.MESSAGES -> MessagesScreen(
            onNewMessage = {
                onNavigate(AppScreen.COMPOSE)
            },
            onConversationClick = {
                onNavigate(AppScreen.MESSAGE_DETAIL)
            }
        )

        AppScreen.NETWORK -> NetworkScreen()

        AppScreen.DEVICES -> DevicesScreen()

        AppScreen.DIAGNOSTICS -> DiagnosticsScreen()

        AppScreen.COMPOSE -> ComposeMessageScreen()

        AppScreen.MESSAGE_DETAIL -> MessageDetailScreen()
    }
}