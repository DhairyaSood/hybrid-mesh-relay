package com.hybridmesh.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.hybridmesh.relay.AppScreen
import com.hybridmesh.relay.ui.theme.RelayAccent
import com.hybridmesh.relay.ui.theme.RelaySurfaceElevated
import com.hybridmesh.relay.ui.theme.RelayTextMuted

@Composable
fun AppBottomBar(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit
) {
    val items = listOf(
        "⌂" to "Home",
        "◌" to "Network",
        "✉" to "Messages",
        "◉" to "Devices",
        "◎" to "Profile"
    )

    val screens = listOf(
        AppScreen.HOME,
        AppScreen.NETWORK,
        AppScreen.MESSAGES,
        AppScreen.DEVICES,
        AppScreen.PROFILE
    )

    NavigationBar(
        containerColor = RelaySurfaceElevated
    ) {
        items.forEachIndexed { index, (symbol, label) ->
            val selected = currentScreen == screens[index]

            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(screens[index]) },
                icon = {
                    Text(
                        text = symbol,
                        fontSize = 19.sp,
                        color = if (selected) RelayAccent else RelayTextMuted
                    )
                },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = RelayAccent,
                    selectedTextColor = RelayAccent,
                    unselectedIconColor = RelayTextMuted,
                    unselectedTextColor = RelayTextMuted,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}
