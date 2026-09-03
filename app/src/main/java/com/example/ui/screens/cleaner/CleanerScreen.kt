package com.example.ui.screens.cleaner

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.text.format.Formatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerScreen(
    onBack: () -> Unit,
    viewModel: CleanerViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsRepo = remember { UserPreferencesRepository(context) }
    
    val state by viewModel.state.collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Large", "Old", "Duplicates", "Apps")
    
    var showDeleteConfirm by remember { mutableStateOf<List<File>?>(null) }
    
    LaunchedEffect(Unit) {
        viewModel.startScan(context)
    }

    if (showDeleteConfirm != null) {
        val filesToDelete = showDeleteConfirm!!
        val totalSize = filesToDelete.sumOf { it.length() }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Confirm Deletion") },
            text = { Text("Are you sure you want to delete ${filesToDelete.size} file(s)?\nTotal space freed: ${Formatter.formatShortFileSize(context, totalSize)}") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = null
                    scope.launch(Dispatchers.IO) {
                        for (file in filesToDelete) {
                            try {
                                if (file.delete()) {
                                    withContext(Dispatchers.Main) {
                                        viewModel.removeFileFromLists(file)
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Failed to delete ${file.name}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Error deleting ${file.name}: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }

    fun requestDelete(files: List<File>) {
        scope.launch {
            val confirm = prefsRepo.userPreferencesFlow.first().confirmDestructive
            if (confirm) {
                showDeleteConfirm = files
            } else {
                scope.launch(Dispatchers.IO) {
                    for (file in files) {
                        try {
                            if (file.delete()) {
                                withContext(Dispatchers.Main) {
                                    viewModel.removeFileFromLists(file)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Failed to delete ${file.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Storage Cleaner") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (state.isScanning) {
                            TextButton(onClick = { viewModel.cancelScan() }) {
                                Text("Cancel")
                            }
                        } else {
                            TextButton(onClick = { viewModel.startScan(context) }) {
                                Text("Rescan")
                            }
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Scanning... ${state.scannedCount} files checked", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp))
            }
            
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                when (selectedTab) {
                    0 -> { // Large
                        if (state.largeFiles.isEmpty() && !state.isScanning) {
                            item { Text("No large files (>20MB) found.", modifier = Modifier.padding(8.dp)) }
                        }
                        items(state.largeFiles) { file ->
                            FileRow(file, context) { requestDelete(listOf(file)) }
                        }
                    }
                    1 -> { // Old
                        if (state.oldFiles.isEmpty() && !state.isScanning) {
                            item { Text("No old files (>90 days) found.", modifier = Modifier.padding(8.dp)) }
                        }
                        items(state.oldFiles) { file ->
                            FileRow(file, context) { requestDelete(listOf(file)) }
                        }
                    }
                    2 -> { // Duplicates
                        if (state.duplicates.isEmpty() && !state.isScanning) {
                            item { Text("No duplicate files found.", modifier = Modifier.padding(8.dp)) }
                        }
                        items(state.duplicates) { group ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    val size = group.firstOrNull()?.length() ?: 0L
                                    Text("Duplicate Group (${group.size} files, Wasted: ${Formatter.formatShortFileSize(context, size * (group.size - 1))})", style = MaterialTheme.typography.titleSmall)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    group.forEach { file ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                                Text(file.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton(onClick = { requestDelete(listOf(file)) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(onClick = {
                                        // Keep first, delete rest
                                        requestDelete(group.drop(1))
                                    }) {
                                        Text("Keep first, delete rest")
                                    }
                                }
                            }
                        }
                    }
                    3 -> { // Apps
                        item {
                            if (!hasUsageStatsPermission(context)) {
                                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("Usage Access Required", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                        Text("To find unused apps, please grant Usage Access in Settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(onClick = {
                                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }) {
                                            Text("Open Settings")
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        if (state.unusedApps.isEmpty() && !state.isScanning && hasUsageStatsPermission(context)) {
                            item { Text("No unused apps found (all apps used in last 90 days).", modifier = Modifier.padding(8.dp)) }
                        }
                        items(state.unusedApps) { app ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(
                                        bitmap = app.icon.toBitmap().asImageBitmap(),
                                        contentDescription = app.name,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.name, style = MaterialTheme.typography.titleMedium)
                                        Text("Not recently used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                        Text(Formatter.formatShortFileSize(context, app.size), style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        intent.data = Uri.parse("package:${app.packageName}")
                                        context.startActivity(intent)
                                    }) {
                                        Text("Details")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileRow(file: File, context: Context, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge)
                Text(file.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Formatter.formatShortFileSize(context, file.length()), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}
