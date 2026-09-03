package com.example.ui.screens.files

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.UserPreferencesRepository
import com.example.ui.screens.vault.VaultViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.text.format.Formatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    onBack: () -> Unit,
    rootDir: File,
    onNavigateToEditor: (String) -> Unit = {},
    viewModel: FileManagerViewModel = viewModel(),
    vaultViewModel: VaultViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsRepo = remember { UserPreferencesRepository(context) }
    
    val currentDir by viewModel.currentDir.collectAsState()
    val files by viewModel.files.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()

    LaunchedEffect(rootDir) {
        viewModel.init(rootDir)
    }

    val selectionMode = selectedFiles.isNotEmpty()
    
    BackHandler(enabled = currentDir != null && currentDir!!.absolutePath != rootDir.absolutePath || selectionMode || searchQuery.isActive) {
        if (selectionMode) {
            viewModel.clearSelection()
        } else if (searchQuery.isActive) {
            viewModel.setSearchQuery(FileSearchQuery())
        } else {
            viewModel.navigateUp(rootDir)
        }
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    val safPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            scope.launch {
                prefsRepo.updateDefaultExportUri(uri.toString())
                viewModel.zipSelected(context, uri.toString(), "archive_${System.currentTimeMillis()}.zip") { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(context, "Export cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Files") },
            text = { Text("Are you sure you want to delete ${selectedFiles.size} item(s)?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteSelected { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedFiles.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) { Icon(Icons.Default.Close, "Cancel") }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) { Text("All", modifier = Modifier.padding(8.dp)) }
                        var showMore by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMore = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(text = { Text("Zip selected") }, onClick = {
                                showMore = false
                                scope.launch {
                                    val prefs = prefsRepo.userPreferencesFlow.first()
                                    if (prefs.defaultExportUri.isNotEmpty()) {
                                        viewModel.zipSelected(context, prefs.defaultExportUri, "archive_${System.currentTimeMillis()}.zip") { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        viewModel.zipSelected(context, null, "archive_${System.currentTimeMillis()}.zip") { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                    } else {
                                        safPickerLauncher.launch(null)
                                    }
                                }
                            })
                            DropdownMenuItem(text = { Text("Move to Vault") }, onClick = {
                                showMore = false
                                viewModel.moveToVault(context, vaultViewModel) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                            })
                            DropdownMenuItem(text = { Text("Share") }, onClick = {
                                showMore = false
                                val uris = selectedFiles.mapNotNull { file ->
                                    try {
                                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    } catch(e: Exception) { null }
                                }
                                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "*/*"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Files"))
                                viewModel.clearSelection()
                            })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                showMore = false
                                scope.launch {
                                    val confirm = prefsRepo.userPreferencesFlow.first().confirmDestructive
                                    if (confirm) showDeleteConfirm = true
                                    else viewModel.deleteSelected { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                }
                            })
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )
            } else if (searchQuery.isActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery.nameMatch,
                            onValueChange = { viewModel.setSearchQuery(searchQuery.copy(nameMatch = it)) },
                            placeholder = { Text("Search files...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setSearchQuery(FileSearchQuery()) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(currentDir?.name?.takeIf { it.isNotEmpty() } ?: "Files") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (currentDir != null && currentDir!!.absolutePath != rootDir.absolutePath) {
                                viewModel.navigateUp(rootDir)
                            } else {
                                onBack()
                            }
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setSearchQuery(FileSearchQuery(isActive = true)) }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (files.isEmpty()) {
                    item { Text("Folder is empty", modifier = Modifier.padding(16.dp)) }
                }
                items(files) { file ->
                    val isSelected = selectedFiles.contains(file)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        viewModel.toggleSelection(file)
                                    } else {
                                        if (file.isDirectory) {
                                            viewModel.navigateTo(file)
                                        } else if (file.extension.lowercase() == "zip") {
                                            // Extract zip
                                            viewModel.extractZip(file, currentDir!!) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                        } else {
                                            onNavigateToEditor(file.absolutePath)
                                        }
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleSelection(file)
                                }
                            )
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectionMode) {
                            Checkbox(checked = isSelected, onCheckedChange = { viewModel.toggleSelection(file) })
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Icon(
                            imageVector = when {
                                file.isDirectory -> Icons.Default.Folder
                                file.extension.lowercase() == "zip" -> Icons.Default.FolderZip
                                else -> Icons.Default.Description
                            },
                            contentDescription = null,
                            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = file.name, style = MaterialTheme.typography.bodyLarge)
                            Row {
                                val sizeStr = if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else Formatter.formatShortFileSize(context, file.length())
                                val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(file.lastModified()))
                                Text(
                                    text = "$dateStr • $sizeStr",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            
            if (isProcessing) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
