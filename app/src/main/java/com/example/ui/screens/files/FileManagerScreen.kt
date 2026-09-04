package com.example.ui.screens.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.UserPreferencesRepository
import com.example.ui.screens.vault.VaultViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    onBack: () -> Unit,
    rootDir: File,
    onNavigateToEditor: (String) -> Unit = {},
    onNavigateToHex: (String) -> Unit = {},
    onNavigateToHash: (String) -> Unit = {},
    onNavigateToRenamer: (List<String>) -> Unit = {},
    onNavigateToWebShare: () -> Unit = {},
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
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val clipboard by viewModel.clipboard.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val processingMessage by viewModel.processingMessage.collectAsState()
    val analytics by viewModel.analytics.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()

    // Active Dialog States
    var viewerImageFile by remember { mutableStateOf<File?>(null) }
    var viewerVideoFile by remember { mutableStateOf<File?>(null) }
    var viewerAudioFile by remember { mutableStateOf<File?>(null) }
    var viewerPdfFile by remember { mutableStateOf<File?>(null) }
    var viewerZipFile by remember { mutableStateOf<File?>(null) }
    var propertiesFile by remember { mutableStateOf<File?>(null) }
    var fileActionTarget by remember { mutableStateOf<File?>(null) }

    var showAnalyticsSheet by remember { mutableStateOf(false) }
    var showSearchFilterDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMainMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<File?>(null) }
    var showZipCreateDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf<List<File>?>(null) }
    var showPermissionHelpDialog by remember { mutableStateOf(false) }

    // Init with UserPreferences
    LaunchedEffect(rootDir) {
        val prefs = prefsRepo.userPreferencesFlow.first()
        viewModel.init(rootDir, prefs.fileFavorites, prefs.showHiddenFiles)
    }

    val selectionMode = selectedFiles.isNotEmpty()

    // Android back handling
    BackHandler(
        enabled = (currentDir != null && currentDir!!.absolutePath != rootDir.absolutePath) || selectionMode || searchQuery.isActive
    ) {
        when {
            selectionMode -> viewModel.clearSelection()
            searchQuery.isActive -> viewModel.setSearchQuery(FileSearchQuery(isActive = false))
            else -> viewModel.navigateUp(rootDir)
        }
    }

    // SAF Picker for ZIP Export
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

    // Helper functions for file sharing and opening
    fun shareFiles(filesToShare: List<File>) {
        try {
            val uris = filesToShare.mapNotNull { file ->
                try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    null
                }
            }
            if (uris.isEmpty()) {
                Toast.makeText(context, "Cannot share selected file(s)", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = FileUtils.getMimeType(filesToShare.first())
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWithExternalApp(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = FileUtils.getMimeType(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(context, "No external app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    // Robust file dispatcher
    fun handleFileClick(file: File) {
        try {
            if (!file.exists()) {
                Toast.makeText(context, "File does not exist or was moved", Toast.LENGTH_SHORT).show()
                viewModel.refresh()
                return
            }
            if (file.isDirectory) {
                viewModel.navigateTo(file)
                return
            }

            viewModel.recordRecent(file)
            val type = FileUtils.getFileType(file)
            when (type) {
                FileType.IMAGE -> viewerImageFile = file
                FileType.VIDEO -> viewerVideoFile = file
                FileType.AUDIO -> viewerAudioFile = file
                FileType.PDF -> viewerPdfFile = file
                FileType.ARCHIVE -> viewerZipFile = file
                FileType.CODE, FileType.TEXT -> onNavigateToEditor(file.absolutePath)
                FileType.APK -> fileActionTarget = file
                FileType.DOCUMENT -> openWithExternalApp(file)
                FileType.UNKNOWN -> {
                    // For files under 1MB, open in the code/text editor for viewing
                    if (file.length() < 1024 * 1024) {
                        onNavigateToEditor(file.absolutePath)
                    } else {
                        fileActionTarget = file
                    }
                }
                FileType.DIRECTORY -> viewModel.navigateTo(file)
            }
        } catch (t: Throwable) {
            Toast.makeText(context, "Unable to open file: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedFiles.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, "Clear Selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (selectedFiles.size == files.size) viewModel.clearSelection() else viewModel.selectAll()
                        }) {
                            Icon(Icons.Default.SelectAll, "Select All")
                        }
                        IconButton(onClick = { viewModel.copySelected() }) {
                            Icon(Icons.Default.ContentCopy, "Copy")
                        }
                        IconButton(onClick = { viewModel.cutSelected() }) {
                            Icon(Icons.Default.ContentCut, "Cut")
                        }
                        IconButton(onClick = {
                            showDeleteConfirmDialog = selectedFiles.toList()
                        }) {
                            Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }

                        var showSelectMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showSelectMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More actions")
                        }
                        DropdownMenu(expanded = showSelectMenu, onDismissRequest = { showSelectMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Invert Selection") },
                                leadingIcon = { Icon(Icons.Default.FlipToBack, null) },
                                onClick = {
                                    showSelectMenu = false
                                    viewModel.invertSelection()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Compress to ZIP") },
                                leadingIcon = { Icon(Icons.Default.FolderZip, null) },
                                onClick = {
                                    showSelectMenu = false
                                    showZipCreateDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Batch Rename") },
                                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                onClick = {
                                    showSelectMenu = false
                                    val paths = selectedFiles.map { it.absolutePath }
                                    viewModel.clearSelection()
                                    onNavigateToRenamer(paths)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Move to Vault") },
                                leadingIcon = { Icon(Icons.Default.Lock, null) },
                                onClick = {
                                    showSelectMenu = false
                                    viewModel.moveToVault(context, vaultViewModel) {
                                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    showSelectMenu = false
                                    shareFiles(selectedFiles.toList())
                                    viewModel.clearSelection()
                                }
                            )
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
                            placeholder = { Text("Search files & subfolders...") },
                            singleLine = true,
                            trailingIcon = {
                                if (searchQuery.nameMatch.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery(searchQuery.copy(nameMatch = "")) }) {
                                        Icon(Icons.Default.Clear, "Clear")
                                    }
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setSearchQuery(FileSearchQuery(isActive = false)) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close Search")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearchFilterDialog = true }) {
                            Icon(Icons.Default.FilterList, "Filter Search")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = currentDir?.name?.takeIf { it.isNotEmpty() } ?: "Files",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${files.size} items",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (currentDir != null && currentDir!!.absolutePath != rootDir.absolutePath) {
                                viewModel.navigateUp(rootDir)
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setSearchQuery(FileSearchQuery(isActive = true)) }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                        IconButton(onClick = {
                            showAnalyticsSheet = true
                            viewModel.loadStorageAnalytics(rootDir)
                        }) {
                            Icon(Icons.Default.PieChart, "Storage Analytics")
                        }
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, "Sort")
                        }
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(if (viewMode == FileViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList, "View Mode")
                        }
                        IconButton(onClick = { showMainMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More Options")
                        }

                        // Sort Menu Dropdown
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortBy.entries.forEach { sortOption ->
                                DropdownMenuItem(
                                    text = { Text(sortOption.label) },
                                    leadingIcon = {
                                        if (sortBy == sortOption) {
                                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    onClick = {
                                        viewModel.setSortBy(sortOption)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }

                        // Main Menu Dropdown
                        DropdownMenu(expanded = showMainMenu, onDismissRequest = { showMainMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("New Folder") },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                onClick = {
                                    showMainMenu = false
                                    showNewFolderDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("New Empty File") },
                                leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                                onClick = {
                                    showMainMenu = false
                                    showNewFileDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (showHidden) "Hide Hidden Files" else "Show Hidden Files") },
                                leadingIcon = { Icon(if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) },
                                onClick = {
                                    showMainMenu = false
                                    val newShow = !showHidden
                                    viewModel.setShowHidden(newShow)
                                    scope.launch { prefsRepo.setShowHiddenFiles(newShow) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Wi-Fi Web Share") },
                                leadingIcon = { Icon(Icons.Default.WifiTethering, null) },
                                onClick = {
                                    showMainMenu = false
                                    onNavigateToWebShare()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Batch Renamer") },
                                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                onClick = {
                                    showMainMenu = false
                                    onNavigateToRenamer(emptyList())
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = {
                                    showMainMenu = false
                                    viewModel.refresh()
                                }
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode && !searchQuery.isActive) {
                FloatingActionButton(
                    onClick = { showNewFolderDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, "Create New")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Android 11+ Scoped Storage banner if permission not granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "App Storage Mode Active (Safe & Full Access)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showPermissionHelpDialog = true }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Info, "Permission info", tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Emulator ya system security ki wajah se 'All files access' disable ho sakta hai. DevVault ka App Storage bilkul ready hai!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showPermissionHelpDialog = true }) {
                                Text("Why auto-disabled?", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(6.dp))
                            FilledTonalButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        context.startActivity(intent)
                                    }
                                }
                            ) {
                                Text("Grant Settings", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Clipboard Paste Bar
            clipboard?.let { clip ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (clip.action == ClipboardAction.COPY) Icons.Default.ContentCopy else Icons.Default.ContentCut,
                            null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${clip.files.size} item(s) ${if (clip.action == ClipboardAction.COPY) "copied" else "cut"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearClipboard() }) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                currentDir?.let { target ->
                                    viewModel.pasteClipboard(target) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste Here")
                        }
                    }
                }
            }

            // Breadcrumbs Navigation Bar (interactive!)
            if (!searchQuery.isActive) {
                val breadcrumbs = remember(currentDir, rootDir) { viewModel.getBreadcrumbs(rootDir) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = "Root",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .combinedClickable(onClick = { viewModel.navigateTo(rootDir) })
                    )

                    breadcrumbs.forEachIndexed { index, folder ->
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        val isLast = index == breadcrumbs.lastIndex
                        Text(
                            text = folder.name.ifEmpty { "Root" },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .combinedClickable(onClick = { viewModel.navigateTo(folder) })
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Category Filter Chips Bar
            if (!searchQuery.isActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FileCategory.entries.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { viewModel.setCategory(category) },
                            label = { Text(category.label) },
                            leadingIcon = {
                                val icon = when (category) {
                                    FileCategory.ALL -> Icons.Default.Folder
                                    FileCategory.IMAGES -> Icons.Default.Image
                                    FileCategory.VIDEOS -> Icons.Default.Videocam
                                    FileCategory.AUDIO -> Icons.Default.Audiotrack
                                    FileCategory.DOCUMENTS -> Icons.Default.Description
                                    FileCategory.DOWNLOADS -> Icons.Default.Download
                                    FileCategory.ARCHIVES -> Icons.Default.FolderZip
                                    FileCategory.CODE -> Icons.Default.Code
                                    FileCategory.APKS -> Icons.Default.Android
                                }
                                Icon(icon, null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            // Search History Chips (when search is active but query is empty)
            if (searchQuery.isActive && searchQuery.nameMatch.isEmpty() && searchHistory.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Recent Searches", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        searchHistory.forEach { historyQuery ->
                            SuggestionChip(
                                onClick = { viewModel.setSearchQuery(searchQuery.copy(nameMatch = historyQuery)) },
                                label = { Text(historyQuery) },
                                icon = { Icon(Icons.Default.History, null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                    }
                }
            }

            // Files Container (List or Grid)
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                if (files.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (searchQuery.isActive) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isActive) "No files match your query" else "This folder is empty",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!searchQuery.isActive) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Tap + to add a new folder or file",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else if (viewMode == FileViewMode.LIST) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(files, key = { it.absolutePath }) { file ->
                            val isSelected = selectedFiles.contains(file)
                            val isFav = favorites.contains(file.absolutePath)

                            FileListItem(
                                file = file,
                                isSelected = isSelected,
                                selectionMode = selectionMode,
                                isFavorite = isFav,
                                onFavoriteToggle = {
                                    scope.launch {
                                        prefsRepo.toggleFavorite(file.absolutePath)
                                        val newFavs = if (isFav) favorites - file.absolutePath else favorites + file.absolutePath
                                        viewModel.setFavorites(newFavs)
                                    }
                                },
                                onClick = {
                                    if (selectionMode) {
                                        viewModel.toggleSelection(file)
                                    } else {
                                        handleFileClick(file)
                                    }
                                },
                                onLongClick = {
                                    if (selectionMode) {
                                        viewModel.toggleSelection(file)
                                    } else {
                                        fileActionTarget = file
                                    }
                                },
                                onMoreClick = { fileActionTarget = file }
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(files, key = { it.absolutePath }) { file ->
                            val isSelected = selectedFiles.contains(file)
                            FileGridItem(
                                file = file,
                                isSelected = isSelected,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        viewModel.toggleSelection(file)
                                    } else {
                                        handleFileClick(file)
                                    }
                                },
                                onLongClick = {
                                    if (selectionMode) {
                                        viewModel.toggleSelection(file)
                                    } else {
                                        fileActionTarget = file
                                    }
                                }
                            )
                        }
                    }
                }

                // Progress Indicator
                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(processingMessage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // CONTEXTUAL ACTION BOTTOM SHEET
    // ==========================================
    fileActionTarget?.let { file ->
        val isFav = favorites.contains(file.absolutePath)
        ModalBottomSheet(
            onDismissRequest = { fileActionTarget = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else FileUtils.formatSize(context, file.length()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()

                ActionMenuItem("Open", Icons.Default.OpenInBrowser) {
                    fileActionTarget = null
                    handleFileClick(file)
                }
                if (!file.isDirectory) {
                    ActionMenuItem("Open With...", Icons.Default.OpenInNew) {
                        fileActionTarget = null
                        openWithExternalApp(file)
                    }
                    ActionMenuItem("Share", Icons.Default.Share) {
                        fileActionTarget = null
                        shareFiles(listOf(file))
                    }
                    ActionMenuItem("Open as Text in Code Editor", Icons.Default.Code) {
                        fileActionTarget = null
                        onNavigateToEditor(file.absolutePath)
                    }
                    ActionMenuItem("Open in Hex & Binary Viewer", Icons.Default.DataArray) {
                        val target = file
                        fileActionTarget = null
                        onNavigateToHex(target.absolutePath)
                    }
                    ActionMenuItem("Calculate Hash (MD5 / SHA-256)", Icons.Default.Calculate) {
                        val target = file
                        fileActionTarget = null
                        onNavigateToHash(target.absolutePath)
                    }
                }
                ActionMenuItem("Copy", Icons.Default.ContentCopy) {
                    fileActionTarget = null
                    viewModel.toggleSelection(file)
                    viewModel.copySelected()
                }
                ActionMenuItem("Cut / Move", Icons.Default.ContentCut) {
                    fileActionTarget = null
                    viewModel.toggleSelection(file)
                    viewModel.cutSelected()
                }
                ActionMenuItem("Duplicate", Icons.Default.ControlPointDuplicate) {
                    fileActionTarget = null
                    viewModel.duplicateFile(file) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                }
                ActionMenuItem("Rename", Icons.Default.DriveFileRenameOutline) {
                    val target = file
                    fileActionTarget = null
                    showRenameDialog = target
                }
                ActionMenuItem(if (isFav) "Remove from Favorites" else "Add to Favorites", if (isFav) Icons.Default.Star else Icons.Default.StarBorder) {
                    fileActionTarget = null
                    scope.launch {
                        prefsRepo.toggleFavorite(file.absolutePath)
                        val newFavs = if (isFav) favorites - file.absolutePath else favorites + file.absolutePath
                        viewModel.setFavorites(newFavs)
                    }
                }
                ActionMenuItem("Properties & Details", Icons.Default.Info) {
                    val target = file
                    fileActionTarget = null
                    propertiesFile = target
                }
                ActionMenuItem("Delete", Icons.Default.Delete, textColor = MaterialTheme.colorScheme.error) {
                    val target = file
                    fileActionTarget = null
                    showDeleteConfirmDialog = listOf(target)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ==========================================
    // ALL DIALOGS (VIEWERS, RENAMES, ETC.)
    // ==========================================

    // Image Viewer
    viewerImageFile?.let { file ->
        ImageViewerDialog(
            file = file,
            onDismiss = { viewerImageFile = null },
            onShare = { shareFiles(listOf(file)) },
            onOpenWith = { openWithExternalApp(file) }
        )
    }

    // Video Player
    viewerVideoFile?.let { file ->
        VideoPlayerDialog(
            file = file,
            onDismiss = { viewerVideoFile = null },
            onOpenWith = { openWithExternalApp(file) }
        )
    }

    // Audio Player
    viewerAudioFile?.let { file ->
        AudioPlayerDialog(
            file = file,
            onDismiss = { viewerAudioFile = null },
            onOpenWith = { openWithExternalApp(file) }
        )
    }

    // PDF Viewer
    viewerPdfFile?.let { file ->
        PdfViewerDialog(
            file = file,
            onDismiss = { viewerPdfFile = null },
            onOpenWith = { openWithExternalApp(file) }
        )
    }

    // ZIP Inspector
    viewerZipFile?.let { file ->
        ZipViewerDialog(
            file = file,
            onDismiss = { viewerZipFile = null },
            onExtractAll = {
                val target = currentDir ?: file.parentFile ?: rootDir
                viewerZipFile = null
                viewModel.extractZip(file, target) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }
        )
    }

    // Properties Dialog
    propertiesFile?.let { file ->
        FilePropertiesDialog(
            file = file,
            onDismiss = { propertiesFile = null }
        )
    }

    // Permission & Storage Help Dialog
    if (showPermissionHelpDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionHelpDialog = false },
            icon = { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Permission & Storage Guide") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Permission kyun foran disable ho rahi hai?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "1. Cloud Emulator Restriction: Virtual emulators ya container environment mein Android OS ki security policy 'All files access' ko foran revert kar deti hai.\n\n" +
                        "2. DevVault Bilkul Mehfooz Hai: DevVault ka 'App Storage' mode mukammal kaam karta hai! Aap files create kar sakte hain, code edit kar sakte hain, ZIP extract kar sakte hain aur Vault mein encrypt kar sakte hain.\n\n" +
                        "3. Physical Phone Par Solution: Agar aap real phone use kar rahe hain to Settings ko recent apps se close karein, aur phir dobara 'Grant Settings' open karein.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showPermissionHelpDialog = false
                    val appStorage = context.getExternalFilesDir(null) ?: context.filesDir
                    viewModel.switchStorageLocation(appStorage)
                }) {
                    Text("Use App Storage")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionHelpDialog = false
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        context.startActivity(intent)
                    }
                }) {
                    Text("Try Settings Again")
                }
            }
        )
    }

    // Storage Analytics & Cleaner Sheet
    if (showAnalyticsSheet) {
        StorageAnalyticsSheet(
            analytics = analytics,
            isLoading = isAnalyzing,
            onDeleteFile = { file ->
                viewModel.deleteFiles(listOf(file)) {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                    viewModel.loadStorageAnalytics(rootDir)
                }
            },
            onDeleteEmptyFolder = { folder ->
                viewModel.deleteFiles(listOf(folder)) {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                    viewModel.loadStorageAnalytics(rootDir)
                }
            },
            onDismiss = { showAnalyticsSheet = false }
        )
    }

    // New Folder Dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createNewFolder(folderName) { success, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        if (success) showNewFolderDialog = false
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // New Empty File Dialog
    if (showNewFileDialog) {
        var fileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("New File") },
            text = {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File name (e.g. notes.txt)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createNewFile(fileName) { success, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        if (success) showNewFileDialog = false
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename Dialog
    showRenameDialog?.let { file ->
        var newName by remember { mutableStateOf(file.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameFile(file, newName) { success, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        if (success) showRenameDialog = null
                    }
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create Zip Dialog
    if (showZipCreateDialog) {
        var zipName by remember { mutableStateOf("archive_${System.currentTimeMillis()}") }
        AlertDialog(
            onDismissRequest = { showZipCreateDialog = false },
            title = { Text("Compress to ZIP") },
            text = {
                Column {
                    Text("Compressing ${selectedFiles.size} selected item(s):", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = zipName,
                        onValueChange = { zipName = it },
                        label = { Text("Archive name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showZipCreateDialog = false
                    viewModel.createZip(selectedFiles.toList(), zipName) {
                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Compress")
                }
            },
            dismissButton = {
                TextButton(onClick = { showZipCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    showDeleteConfirmDialog?.let { list ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Delete Confirmation") },
            text = {
                Text("Are you sure you want to permanently delete ${list.size} item(s)? This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = null
                    viewModel.deleteFiles(list) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Advanced Search Filter Dialog
    if (showSearchFilterDialog) {
        var isRecursive by remember { mutableStateOf(searchQuery.isRecursive) }
        var extFilter by remember { mutableStateOf(searchQuery.extensionMatch) }
        var dateOption by remember { mutableStateOf(searchQuery.dateFilter) }

        AlertDialog(
            onDismissRequest = { showSearchFilterDialog = false },
            title = { Text("Search Options & Filters") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = isRecursive, onCheckedChange = { isRecursive = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Search subfolders recursively", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extFilter,
                        onValueChange = { extFilter = it },
                        label = { Text("Extension filter (e.g. jpg, pdf, kt)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Modified Date:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("ANY" to "Anytime", "TODAY" to "Today", "WEEK" to "Last 7 days", "MONTH" to "Last 30 days").forEach { (key, label) ->
                            FilterChip(
                                selected = dateOption == key,
                                onClick = { dateOption = key },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setSearchQuery(
                        searchQuery.copy(
                            isRecursive = isRecursive,
                            extensionMatch = extFilter.trim().removePrefix("."),
                            dateFilter = dateOption
                        )
                    )
                    showSearchFilterDialog = false
                }) {
                    Text("Apply Filters")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSearchFilterDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==========================================
// SUBCOMPONENTS
// ==========================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    file: File,
    isSelected: Boolean,
    selectionMode: Boolean,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val context = LocalContext.current
    val type = FileUtils.getFileType(file)
    val color = when (type) {
        FileType.DIRECTORY -> MaterialTheme.colorScheme.primary
        FileType.IMAGE -> Color(0xFF4CAF50)
        FileType.VIDEO -> Color(0xFFE91E63)
        FileType.AUDIO -> Color(0xFFFF9800)
        FileType.PDF -> Color(0xFFE53935)
        FileType.ARCHIVE -> Color(0xFF9C27B0)
        FileType.CODE -> Color(0xFF00BCD4)
        FileType.APK -> Color(0xFF8BC34A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val icon = when (type) {
        FileType.DIRECTORY -> Icons.Default.Folder
        FileType.IMAGE -> Icons.Default.Image
        FileType.VIDEO -> Icons.Default.Videocam
        FileType.AUDIO -> Icons.Default.Audiotrack
        FileType.PDF -> Icons.Default.PictureAsPdf
        FileType.ARCHIVE -> Icons.Default.FolderZip
        FileType.CODE -> Icons.Default.Code
        FileType.APK -> Icons.Default.Android
        FileType.TEXT -> Icons.Default.Article
        FileType.DOCUMENT -> Icons.Default.Description
        FileType.UNKNOWN -> Icons.Default.InsertDriveFile
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
        }

        Box(
            modifier = Modifier
                .size(42.dp)
                .background(color.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val sizeStr = if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else FileUtils.formatSize(context, file.length())
            val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(file.lastModified()))
            Text(
                text = "$dateStr • $sizeStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!selectionMode) {
            IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onMoreClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridItem(
    file: File,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val type = FileUtils.getFileType(file)
    val color = when (type) {
        FileType.DIRECTORY -> MaterialTheme.colorScheme.primary
        FileType.IMAGE -> Color(0xFF4CAF50)
        FileType.VIDEO -> Color(0xFFE91E63)
        FileType.AUDIO -> Color(0xFFFF9800)
        FileType.PDF -> Color(0xFFE53935)
        FileType.ARCHIVE -> Color(0xFF9C27B0)
        FileType.CODE -> Color(0xFF00BCD4)
        FileType.APK -> Color(0xFF8BC34A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val icon = when (type) {
        FileType.DIRECTORY -> Icons.Default.Folder
        FileType.IMAGE -> Icons.Default.Image
        FileType.VIDEO -> Icons.Default.Videocam
        FileType.AUDIO -> Icons.Default.Audiotrack
        FileType.PDF -> Icons.Default.PictureAsPdf
        FileType.ARCHIVE -> Icons.Default.FolderZip
        FileType.CODE -> Icons.Default.Code
        FileType.APK -> Icons.Default.Android
        FileType.TEXT -> Icons.Default.Article
        FileType.DOCUMENT -> Icons.Default.Description
        FileType.UNKNOWN -> Icons.Default.InsertDriveFile
    }

    Card(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.align(Alignment.TopStart).size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(color.copy(alpha = 0.12f), CircleShape)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                file.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            val sizeStr = if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else FileUtils.formatSize(context, file.length())
            Text(
                sizeStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionMenuItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = textColor, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = textColor)
    }
}
