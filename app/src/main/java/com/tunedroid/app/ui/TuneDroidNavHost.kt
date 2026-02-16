package com.tunedroid.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tunedroid.app.data.repository.DownloadRepository
import com.tunedroid.app.engine.FormatPreset
import com.tunedroid.app.ui.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class Screen(val route: String, val label: String) {
    data object FirstLaunch : Screen("first_launch", "Welcome")
    data object Home : Screen("home", "Home")
    data object Downloads : Screen("downloads", "Library")
    data object Settings : Screen("settings", "Settings")
}

private fun getReadPermission(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

private fun hasReadPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(context, getReadPermission()) ==
        PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneDroidNavHost(
    sharedUrl: String?,
    onSharedUrlConsumed: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("tunedroid_prefs", 0)
    val hasSeenWelcome = prefs.getBoolean("has_seen_welcome", false)
    val startDestination = if (hasSeenWelcome) Screen.Home.route else Screen.FirstLaunch.route

    val repository = remember { DownloadRepository(context) }

    // Hoisted HomeScreen state — survives navigation between tabs
    var homeUrlText by remember { mutableStateOf("") }
    var homeState by remember { mutableStateOf<HomeState>(HomeState.Empty) }
    var homeSelectedPreset by remember { mutableStateOf(FormatPreset.MP3_128) }

    // Permission launcher for file recovery after reinstall
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    repository.recoverExistingFiles()
                }
            }
        }
    }

    // Early recovery: check if DB is empty and files exist on disk
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dbCount = repository.getTotalCount()
            if (dbCount > 0) return@withContext

            // Check if Downloads/TuneDroid directory exists (indicates prior install)
            val prefsManager = com.tunedroid.app.data.PreferencesManager(context)
            val storagePath = prefsManager.storagePath.first()
            val storageDir = File(storagePath)
            if (!storageDir.exists()) return@withContext

            if (hasReadPermission(context)) {
                // Already have permission — recover immediately
                repository.recoverExistingFiles()
            } else {
                // Request permission on main thread
                withContext(Dispatchers.Main) {
                    permissionLauncher.launch(getReadPermission())
                }
            }
        }
    }

    val bottomNavItems = listOf(Screen.Home, Screen.Downloads)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.hierarchy?.any { dest ->
        bottomNavItems.any { it.route == dest.route }
    } == true

    val showSettingsIcon = showBottomBar

    Scaffold(
        topBar = {
            if (showSettingsIcon) {
                TopAppBar(
                    title = { Text("TuneDroid") },
                    actions = {
                        IconButton(onClick = {
                            navController.navigate(Screen.Settings.route)
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        val icon = when (screen) {
                            Screen.Home -> Icons.Default.Home
                            Screen.Downloads -> Icons.Default.LibraryMusic
                            else -> Icons.Default.Home
                        }
                        NavigationBarItem(
                            icon = { Icon(icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
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
            composable(Screen.FirstLaunch.route) {
                FirstLaunchScreen(
                    onContinue = {
                        prefs.edit().putBoolean("has_seen_welcome", true).apply()
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.FirstLaunch.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Home.route) {
                HomeScreen(
                    sharedUrl = sharedUrl,
                    onSharedUrlConsumed = onSharedUrlConsumed,
                    urlText = homeUrlText,
                    onUrlTextChange = { homeUrlText = it },
                    homeState = homeState,
                    onHomeStateChange = { homeState = it },
                    selectedPreset = homeSelectedPreset,
                    onSelectedPresetChange = { homeSelectedPreset = it },
                    onNavigateToDownloads = {
                        navController.navigate(Screen.Downloads.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Downloads.route) {
                DownloadsScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
