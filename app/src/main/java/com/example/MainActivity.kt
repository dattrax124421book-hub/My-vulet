package com.example

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.UserPreferencesRepository
import com.example.ui.screens.ComingSoonScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.notes.NotesScreen
import com.example.ui.screens.editor.CodeEditorScreen
import com.example.ui.screens.files.FileManagerScreen
import com.example.ui.screens.network.NetworkScreen
import com.example.ui.screens.network.WebShareScreen
import com.example.ui.screens.apps.AppsScreen
import com.example.ui.screens.apk.ApkToolsScreen
import com.example.ui.screens.cleaner.CleanerScreen
import com.example.ui.screens.vault.VaultScreen
import com.example.ui.screens.contacts.ContactsScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.hash.HashCalculatorScreen
import com.example.ui.screens.hex.HexViewerScreen
import com.example.ui.screens.renamer.BatchRenamerScreen
import com.example.ui.screens.terminal.TerminalScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class MainActivity : FragmentActivity() {
    private lateinit var prefsRepo: UserPreferencesRepository
    private var backgroundTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefsRepo = UserPreferencesRepository(this)
        
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundTime = System.currentTimeMillis()
            }
            override fun onStart(owner: LifecycleOwner) {
                if (backgroundTime > 0) {
                    lifecycleScope.launch {
                        val prefs = prefsRepo.userPreferencesFlow.first()
                        val timeout = prefs.vaultLockTimeout
                        if (timeout != -1L) {
                            val timeInBackground = System.currentTimeMillis() - backgroundTime
                            if (timeInBackground >= timeout) {
                                // Need to signal VaultViewModel to lock.
                                // In a real architecture, we would have a central AuthManager.
                                // For now, we will store a global lock flag or use a broadcast.
                                // Setting a shared preference is a simple cross-component way.
                                val sharedPrefs = getSharedPreferences("vault_state", MODE_PRIVATE)
                                sharedPrefs.edit().putBoolean("force_lock", true).apply()
                            }
                        }
                    }
                }
            }
        })

        enableEdgeToEdge()
        setContent {
            val prefs by prefsRepo.userPreferencesFlow.collectAsState(initial = null)
            
            val isDarkTheme = when (prefs?.theme) {
                "LIGHT" -> false
                "DARK" -> true
                else -> isSystemInDarkTheme()
            }

            MyApplicationTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DevVaultApp()
                }
            }
        }
    }
}


@Composable
fun DevVaultApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") {
            com.example.ui.screens.splash.SplashScreen(onNavigateToHome = {
                navController.navigate("home") {
                    popUpTo("splash") { inclusive = true }
                }
            })
        }
        composable("home") {
            HomeScreen(onNavigate = { route -> navController.navigate(route) })
        }
        composable("file_manager") { 
            val context = androidx.compose.ui.platform.LocalContext.current
            val externalRoot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (android.os.Environment.isExternalStorageManager()) {
                    android.os.Environment.getExternalStorageDirectory()
                } else {
                    context.getExternalFilesDir(null) ?: context.filesDir
                }
            } else {
                android.os.Environment.getExternalStorageDirectory()
            }
            FileManagerScreen(
                onBack = { navController.popBackStack() }, 
                rootDir = externalRoot, 
                onNavigateToEditor = { path -> navController.navigate("code_editor?filePath=${android.net.Uri.encode(path)}") },
                onNavigateToHex = { path -> navController.navigate("hex_viewer?filePath=${android.net.Uri.encode(path)}") },
                onNavigateToHash = { path -> navController.navigate("hash_calc?filePath=${android.net.Uri.encode(path)}") },
                onNavigateToRenamer = { navController.navigate("batch_renamer") },
                onNavigateToWebShare = { navController.navigate("web_share") }
            ) 
        }
        composable(
            route = "code_editor?filePath={filePath}",
            arguments = listOf(
                navArgument("filePath") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val rawFilePath = backStackEntry.arguments?.getString("filePath") 
            val filePath = rawFilePath?.let {
                try { android.net.Uri.decode(it) } catch (e: Exception) { it }
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            CodeEditorScreen(onBack = { navController.popBackStack() }, filesDir = context.filesDir, initialFilePath = filePath) 
        }
        composable(
            route = "hex_viewer?filePath={filePath}",
            arguments = listOf(
                navArgument("filePath") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val rawFilePath = backStackEntry.arguments?.getString("filePath")
            val filePath = rawFilePath?.let {
                try { android.net.Uri.decode(it) } catch (e: Exception) { it }
            }
            HexViewerScreen(initialFilePath = filePath, onBack = { navController.popBackStack() })
        }
        composable(
            route = "hash_calc?filePath={filePath}",
            arguments = listOf(
                navArgument("filePath") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val rawFilePath = backStackEntry.arguments?.getString("filePath")
            val filePath = rawFilePath?.let {
                try { android.net.Uri.decode(it) } catch (e: Exception) { it }
            }
            HashCalculatorScreen(initialFilePath = filePath, onBack = { navController.popBackStack() })
        }
        composable("batch_renamer") {
            BatchRenamerScreen(onBack = { navController.popBackStack() })
        }
        composable("terminal") {
            TerminalScreen(onBack = { navController.popBackStack() })
        }
        composable("web_share") {
            WebShareScreen(onBack = { navController.popBackStack() })
        }
        composable("apk_tools") { 
            ApkToolsScreen(onBack = { navController.popBackStack() }) 
        }
        composable("apps") { 
            AppsScreen(onBack = { navController.popBackStack() }) 
        }
        composable("network") { 
            NetworkScreen(onBack = { navController.popBackStack() }) 
        }
        composable("cleaner") { 
            CleanerScreen(onBack = { navController.popBackStack() }) 
        }
        composable("notes") { 
            NotesScreen(onBack = { navController.popBackStack() }) 
        }
        composable("contacts") { 
            ContactsScreen(onBack = { navController.popBackStack() }) 
        }
        composable("vault") { 
            VaultScreen(onBack = { navController.popBackStack() }) 
        }
        composable("extractor") {
             com.example.ui.screens.extractor.ExtractorScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") { 
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

