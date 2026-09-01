package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.AdminPanelScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.MainChatScreen
import com.example.ui.screens.ScreenAssistantHubScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.OmniAITheme
import com.example.ui.viewmodel.ChatViewModel

object AppRoutes {
    const val CHAT = "chat"
    const val FLOATING_HUB = "floating_hub"
    const val HISTORY = "history"
    const val ADMIN = "admin"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialConvId = intent?.getStringExtra("CONVERSATION_ID")
        val initialNavTarget = intent?.getStringExtra("NAV_TARGET")

        if (!initialConvId.isNullOrBlank()) {
            viewModel.selectConversation(initialConvId)
        }

        setContent {
            val adminSettings by viewModel.adminSettings.collectAsState()
            val isDark = when (adminSettings.appTheme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            OmniAITheme(darkTheme = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OmniAIAppNavigation(
                        viewModel = viewModel,
                        initialNavTarget = initialNavTarget
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val convId = intent.getStringExtra("CONVERSATION_ID")
        if (!convId.isNullOrBlank()) {
            viewModel.selectConversation(convId)
        }
    }
}

@Composable
fun OmniAIAppNavigation(
    viewModel: ChatViewModel,
    initialNavTarget: String?
) {
    val navController = rememberNavController()

    LaunchedEffect(initialNavTarget) {
        if (initialNavTarget == "settings") {
            navController.navigate(AppRoutes.SETTINGS)
        } else if (initialNavTarget == "floating_hub") {
            navController.navigate(AppRoutes.FLOATING_HUB)
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppRoutes.CHAT
    ) {
        composable(AppRoutes.CHAT) {
            MainChatScreen(
                viewModel = viewModel,
                onNavigateToHistory = { navController.navigate(AppRoutes.HISTORY) },
                onNavigateToFloatingHub = { navController.navigate(AppRoutes.FLOATING_HUB) },
                onNavigateToAdmin = { navController.navigate(AppRoutes.ADMIN) },
                onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) }
            )
        }

        composable(AppRoutes.FLOATING_HUB) {
            ScreenAssistantHubScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.HISTORY) {
            HistoryScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onSelectConversation = {
                    navController.popBackStack(AppRoutes.CHAT, inclusive = false)
                }
            )
        }

        composable(AppRoutes.ADMIN) {
            AdminPanelScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAdmin = { navController.navigate(AppRoutes.ADMIN) }
            )
        }
    }
}
