package com.example.ui.screens.splash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onNavigateToHome: () -> Unit) {
    val context = LocalContext.current
    var hasHandledPermissions by remember { mutableStateOf(false) }
    var showManageStorageDialog by remember { mutableStateOf(false) }

    val requiredPermissions = mutableListOf<String>().apply {
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    fun checkAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                showManageStorageDialog = true
            } else {
                hasHandledPermissions = true
            }
        } else {
            hasHandledPermissions = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkAllFilesAccess()
    }

    LaunchedEffect(Unit) {
        delay(1000) // Small splash delay
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            checkAllFilesAccess()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    LaunchedEffect(hasHandledPermissions) {
        if (hasHandledPermissions) {
            onNavigateToHome()
        }
    }

    if (showManageStorageDialog) {
        AlertDialog(
            onDismissRequest = { hasHandledPermissions = true; showManageStorageDialog = false },
            title = { Text("All Files Access Required") },
            text = { Text("To manage your files and backups, DevVault requires 'All Files Access'. Please enable it in Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    showManageStorageDialog = false
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val fallbackIntent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(fallbackIntent)
                        } catch (e2: Exception) {
                            try {
                                val detailsIntent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(detailsIntent)
                            } catch (_: Exception) {}
                        }
                    }
                    hasHandledPermissions = true
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showManageStorageDialog = false
                    hasHandledPermissions = true 
                }) { Text("Skip") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DevVault", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            if (!hasHandledPermissions) {
                Text("Checking permissions...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
