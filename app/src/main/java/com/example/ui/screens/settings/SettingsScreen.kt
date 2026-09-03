package com.example.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel()
    val prefs by viewModel.userPreferences.collectAsState()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showTabSizeDialog by remember { mutableStateOf(false) }
    var showAutosaveDialog by remember { mutableStateOf(false) }

    var showPrivacyPolicy by remember { mutableStateOf(false) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val exportFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            viewModel.updateDefaultExportUri(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { padding ->
        if (prefs == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val p = prefs!!

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                SettingItem(
                    icon = Icons.Default.Palette, 
                    title = "Theme", 
                    subtitle = p.theme,
                    onClick = { showThemeDialog = true }
                )
            }
            item {
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Security", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                val timeoutText = when(p.vaultLockTimeout) {
                    0L -> "Immediately"
                    30000L -> "30 seconds"
                    60000L -> "1 minute"
                    300000L -> "5 minutes"
                    -1L -> "Never"
                    else -> "${p.vaultLockTimeout} ms"
                }
                SettingItem(
                    icon = Icons.Default.Security, 
                    title = "Vault Lock Timeout", 
                    subtitle = timeoutText,
                    onClick = { showTimeoutDialog = true }
                )
                SettingSwitchItem(
                    icon = Icons.Default.Security, 
                    title = "Biometric Unlock", 
                    subtitle = "Use device biometrics to unlock Vault",
                    checked = p.biometricUnlock,
                    onCheckedChange = { viewModel.updateBiometricUnlock(it) }
                )
                
                val devicePolicyManager = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminComponent = android.content.ComponentName(context, com.example.admin.DevVaultAdminReceiver::class.java)
                val isAdminActive = devicePolicyManager.isAdminActive(adminComponent)
                
                SettingItem(
                    icon = Icons.Default.Security,
                    title = "App Uninstall Protection",
                    subtitle = if (isAdminActive) "Enabled. Tap to disable (requires auth)." else "Disabled. Tap to enable.",
                    onClick = {
                        if (!isAdminActive) {
                            val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enabling this will prevent the app from being uninstalled without authentication.")
                            context.startActivity(intent)
                        } else {
                            devicePolicyManager.removeActiveAdmin(adminComponent)
                            android.widget.Toast.makeText(context, "Uninstall Protection Disabled", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            item {
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Editor", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                SettingSliderItem(
                    icon = Icons.Default.Code,
                    title = "Font Size",
                    value = p.editorFontSize,
                    valueRange = 10f..24f,
                    onValueChange = { viewModel.updateEditorFontSize(it) },
                    valueText = "${p.editorFontSize.toInt()} sp"
                )
                SettingSwitchItem(
                    icon = Icons.Default.Code,
                    title = "Monospace Font",
                    subtitle = "Use monospace font family",
                    checked = p.editorFontFamily == "MONOSPACE",
                    onCheckedChange = { viewModel.updateEditorFontFamily(if(it) "MONOSPACE" else "DEFAULT") }
                )
                SettingItem(
                    icon = Icons.Default.Code, 
                    title = "Tab Size", 
                    subtitle = "${p.editorTabSize} spaces",
                    onClick = { showTabSizeDialog = true }
                )
                SettingSwitchItem(
                    icon = Icons.Default.Code,
                    title = "Use Spaces for Tabs",
                    subtitle = "Insert spaces when Tab is pressed",
                    checked = p.editorUseSpaces,
                    onCheckedChange = { viewModel.updateEditorUseSpaces(it) }
                )
                SettingSwitchItem(
                    icon = Icons.Default.Code,
                    title = "Word Wrap",
                    subtitle = "Wrap long lines in editor",
                    checked = p.editorWordWrap,
                    onCheckedChange = { viewModel.updateEditorWordWrap(it) }
                )
                SettingSwitchItem(
                    icon = Icons.Default.Code,
                    title = "Autosave",
                    subtitle = "Automatically save modified files",
                    checked = p.editorAutosave,
                    onCheckedChange = { viewModel.updateEditorAutosave(it) }
                )
                if (p.editorAutosave) {
                    val intervalText = when(p.editorAutosaveInterval) {
                        5000L -> "5 seconds"
                        15000L -> "15 seconds"
                        30000L -> "30 seconds"
                        else -> "${p.editorAutosaveInterval} ms"
                    }
                    SettingItem(
                        icon = Icons.Default.Save, 
                        title = "Autosave Interval", 
                        subtitle = intervalText,
                        onClick = { showAutosaveDialog = true }
                    )
                }
                SettingSwitchItem(
                    icon = Icons.Default.Code,
                    title = "Automatic Diagnostics",
                    subtitle = "Run syntax checks automatically while typing",
                    checked = p.editorDiagnosticsAuto,
                    onCheckedChange = { viewModel.updateEditorDiagnosticsAuto(it) }
                )
            }
            item {
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("General", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                SettingSwitchItem(
                    icon = Icons.Default.Warning,
                    title = "Confirm Destructive Actions",
                    subtitle = "Require confirmation for deletes",
                    checked = p.confirmDestructive,
                    onCheckedChange = { viewModel.updateConfirmDestructive(it) }
                )
                SettingItem(
                    icon = Icons.Default.Folder,
                    title = "Default Export Folder",
                    subtitle = if (p.defaultExportUri.isNotEmpty()) Uri.parse(p.defaultExportUri).lastPathSegment ?: "Custom Folder" else "Not set",
                    onClick = { exportFolderLauncher.launch(null) }
                )
            }
            item {
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("About", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                SettingItem(icon = Icons.Default.Info, title = "Version", subtitle = BuildConfig.VERSION_NAME, onClick = {})
                SettingItem(icon = Icons.Default.Info, title = "Privacy Policy", subtitle = "View data handling policies", onClick = { showPrivacyPolicy = true })
            }
        }
        
        // Dialogs
        if (showThemeDialog) {
            SelectionDialog(
                title = "Theme",
                options = listOf("SYSTEM", "LIGHT", "DARK"),
                selected = p.theme,
                onSelect = { viewModel.updateTheme(it); showThemeDialog = false },
                onDismiss = { showThemeDialog = false }
            )
        }
        if (showTimeoutDialog) {
            PairSelectionDialog(
                title = "Vault Lock Timeout",
                options = listOf(0L to "Immediately", 30000L to "30 seconds", 60000L to "1 minute", 300000L to "5 minutes", -1L to "Never"),
                selected = p.vaultLockTimeout,
                onSelect = { viewModel.updateVaultLockTimeout(it); showTimeoutDialog = false },
                onDismiss = { showTimeoutDialog = false }
            )
        }
        if (showTabSizeDialog) {
            SelectionDialog(
                title = "Tab Size",
                options = listOf(2, 4, 8),
                selected = p.editorTabSize,
                onSelect = { viewModel.updateEditorTabSize(it); showTabSizeDialog = false },
                onDismiss = { showTabSizeDialog = false },
                valueMapper = { "$it spaces" }
            )
        }
        if (showAutosaveDialog) {
            PairSelectionDialog(
                title = "Autosave Interval",
                options = listOf(5000L to "5 seconds", 15000L to "15 seconds", 30000L to "30 seconds"),
                selected = p.editorAutosaveInterval,
                onSelect = { viewModel.updateEditorAutosaveInterval(it); showAutosaveDialog = false },
                onDismiss = { showAutosaveDialog = false }
            )
        }
        
        if (showPrivacyPolicy) {
            AlertDialog(
                onDismissRequest = { showPrivacyPolicy = false },
                title = { Text("Privacy Policy") },
                text = {
                    Text("This application operates entirely on-device and does not send any of your data to external servers. It does not have the INTERNET permission.\n\n" +
                         "- Contacts: Used only when you initiate a backup or restore action. Backups are encrypted locally.\n" +
                         "- Storage: Used to read and manage files locally on your device, and to securely store items in your Vault.\n" +
                         "- Biometrics: Used exclusively for unlocking the Vault on this device.\n" +
                         "- Usage Access: Used to identify rarely used apps for the cleaner feature.")
                },
                confirmButton = {
                    TextButton(onClick = { showPrivacyPolicy = false }) { Text("Close") }
                }
            )
        }
    }
}

@Composable
fun SettingItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingSwitchItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingSliderItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit, valueText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(valueText, style = MaterialTheme.typography.bodyMedium)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = 14)
        }
    }
}

@Composable
fun <T> SelectionDialog(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    valueMapper: (T) -> String = { it.toString() }
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == option, onClick = { onSelect(option) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(valueMapper(option))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun <T, R> PairSelectionDialog(
    title: String,
    options: List<Pair<T, R>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == value, onClick = { onSelect(value) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label.toString())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
