package com.pocketforge.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketforge.ai.ui.screens.ChatScreen
import com.pocketforge.ai.ui.screens.ModelsScreen
import com.pocketforge.ai.ui.screens.PerformanceScreen
import com.pocketforge.ai.ui.screens.SettingsScreen
import com.pocketforge.ai.ui.theme.PocketForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketForgeTheme {
                AppRoot()
            }
        }
    }
}

private data class NavItem(val route: String, val label: String)

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val items = listOf(
        NavItem("chat", "Chat"),
        NavItem("models", "Models"),
        NavItem("performance", "Performance"),
        NavItem("settings", "Settings"),
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // FLAG_SECURE on the chat window by default (blocks screenshots + recents thumbnail).
    // Off only via the Settings privacy toggle, remembered for this session.
    val settingsVm: com.pocketforge.ai.ui.screens.SettingsViewModel = viewModel(
        initializer = { com.pocketforge.ai.ui.screens.SettingsViewModel(com.pocketforge.ai.PocketForgeApp.container(navController.context)) },
    )
    val privacyState by settingsVm.state.collectAsState()
    val flagsSecure = currentRoute == "chat" && privacyState.flagSecure
    (navController.context as? android.app.Activity)?.window?.let { w ->
        if (flagsSecure) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEach { item ->
                    NavigationBarItem(                        selected = currentRoute == item.route,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (item.route) {
                                    "chat" -> Icons.AutoMirrored.Filled.Send
                                    "models" -> Icons.Filled.Home
                                    "performance" -> Icons.Filled.Star
                                    else -> Icons.Filled.Settings
                                },
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
        ) {
            composable("chat") { ChatScreen() }
            composable("models") { ModelsScreen() }
            composable("performance") { PerformanceScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
