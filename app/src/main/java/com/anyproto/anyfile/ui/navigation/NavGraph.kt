// app/src/main/java/com/anyproto/anyfile/ui/navigation/NavGraph.kt
package com.anyproto.anyfile.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anyproto.anyfile.ui.screens.FilesScreen
import com.anyproto.anyfile.ui.screens.SettingsScreen
import com.anyproto.anyfile.ui.screens.SpacesScreen
import com.anyproto.anyfile.ui.screens.onboarding.OnboardingScreen

/**
 * Sealed class representing all screens in the app
 */
sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Spaces : Screen("spaces", "Spaces", Icons.Filled.Cloud)
    object Files : Screen("files", "Files", Icons.Filled.InsertDriveFile)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

private const val ROUTE_ONBOARDING = "onboarding"

/**
 * Main navigation setup with bottom navigation.
 *
 * If the user has not yet imported a network config the graph starts at the
 * onboarding route; otherwise it starts at the Spaces route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnyFileNavGraph(
    navViewModel: NavViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val items = listOf(
        Screen.Spaces,
        Screen.Files,
        Screen.Settings
    )

    val startDestination = if (navViewModel.isConfigured) {
        Screen.Spaces.route
    } else {
        ROUTE_ONBOARDING
    }

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            // Hide bottom nav on file detail view (when spaceId is present) and on onboarding
            val showBottomBar = currentDestination?.route?.startsWith("files/") != true
                && currentDestination?.route != ROUTE_ONBOARDING

            if (showBottomBar) {
                NavigationBar {
                    items.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true

                        // For Files screen, highlight when on files route
                        val isSelected = when {
                            screen == Screen.Files &&
                            (currentDestination?.route == "files" ||
                             currentDestination?.route?.startsWith("files/") == true) -> true
                            else -> selected
                        }

                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = isSelected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    // Pop up to the start destination of the graph to
                                    // avoid building up a large stack of destinations
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    // Avoid multiple copies of the same destination when
                                    // reselecting the same item
                                    launchSingleTop = true
                                    // Restore state when reselecting a previously selected item
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(ROUTE_ONBOARDING) {
                OnboardingScreen(
                    onOnboardingComplete = {
                        navController.navigate(Screen.Spaces.route) {
                            // Remove onboarding from the back stack so Back doesn't return to it
                            popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Spaces.route) {
                SpacesScreen(
                    onSpaceClick = { spaceId ->
                        navController.navigate("files/$spaceId")
                    }
                )
            }

            composable(
                route = "files",
                arguments = listOf()
            ) {
                FilesScreen(spaceId = "")
            }

            composable(
                route = "files/{spaceId}",
                arguments = listOf(
                    navArgument("spaceId") {
                        type = NavType.StringType
                    }
                )
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId") ?: ""
                FilesScreen(spaceId = spaceId)
            }

            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
