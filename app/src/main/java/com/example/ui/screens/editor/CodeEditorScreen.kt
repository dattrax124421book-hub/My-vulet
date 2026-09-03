package com.example.ui.screens.editor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    onBack: () -> Unit,
    filesDir: File,
    initialFilePath: String? = null
) {
    val viewModel: CodeEditorViewModel = viewModel()
    val tabs by viewModel.tabs.collectAsState()
    val currentIndex by viewModel.currentTabIndex.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val diagnosticIssues by viewModel.diagnosticIssues.collectAsState()
    val context = LocalContext.current

    // UI state
    var showFindReplace by remember { mutableStateOf(false) }
    var showGoToLine by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var wordWrap by remember { mutableStateOf(false) }

    // Find & Replace state
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var ignoreCase by remember { mutableStateOf(true) }
    var matchWord by remember { mutableStateOf(false) }
    var searchMatchCount by remember { mutableIntStateOf(0) }

    // Safely load initial file (if provided)
    LaunchedEffect(initialFilePath) {
        if (!initialFilePath.isNullOrBlank()) {
            try {
                val decodedPath = try {
                    android.net.Uri.decode(initialFilePath)
                } catch (e: Exception) {
                    initialFilePath
                }
                val file = File(decodedPath)
                if (file.exists() && file.isFile) {
                    viewModel.addTab(file)
                }
            } catch (t: Throwable) {
                Toast.makeText(context, "Could not open file: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val currentTab = tabs.getOrNull(currentIndex) ?: EditorTab()

    // Calculate cursor line & column
    val cursorOffset = currentTab.content.selection.start
    val textUpToCursor = currentTab.content.text.take(cursorOffset.coerceIn(0, currentTab.content.text.length))
    val currentLineNumber = textUpToCursor.count { it == '\n' } + 1
    val lastNewlineIndex = textUpToCursor.lastIndexOf('\n')
    val currentColumnNumber = if (lastNewlineIndex >= 0) cursorOffset - lastNewlineIndex else cursorOffset + 1
    val totalLines = maxOf(1, currentTab.content.text.count { it == '\n' } + 1)
    val totalChars = currentTab.content.text.length
    val totalWords = remember(currentTab.content.text) {
        currentTab.content.text.trim().split("\\s+".toRegex()).count { it.isNotEmpty() }
    }
    val detectedLang = viewModel.getDetectedLanguage(currentTab.name)

    // Unsaved changes check
    val hasUnsavedChanges = tabs.any { it.isModified }
    val handleExit = {
        if (hasUnsavedChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = currentTab.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (currentTab.isModified) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "●",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            Text(
                                text = currentTab.file?.parent ?: "Unsaved document",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = handleExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Find / Replace
                        IconButton(onClick = { showFindReplace = !showFindReplace }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Find/Replace",
                                tint = if (showFindReplace) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // Undo
                        IconButton(
                            onClick = { viewModel.undo() },
                            enabled = viewModel.canUndo()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }
                        // Redo
                        IconButton(
                            onClick = { viewModel.redo() },
                            enabled = viewModel.canRedo()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                        }
                        // Save
                        IconButton(onClick = {
                            val ok = viewModel.saveCurrentFile(filesDir)
                            if (ok) {
                                Toast.makeText(context, "Saved: ${currentTab.name}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to save file", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = "Save",
                                tint = if (currentTab.isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // More options
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Go to Line...") },
                                    leadingIcon = { Icon(Icons.Default.Numbers, null) },
                                    onClick = {
                                        showMenu = false
                                        showGoToLine = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save As...") },
                                    leadingIcon = { Icon(Icons.Default.SaveAs, null) },
                                    onClick = {
                                        showMenu = false
                                        showSaveAsDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New Tab") },
                                    leadingIcon = { Icon(Icons.Default.Add, null) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.addTab()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Close Current Tab") },
                                    leadingIcon = { Icon(Icons.Default.Close, null) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.closeTab(currentIndex)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (wordWrap) "Word Wrap: ON" else "Word Wrap: OFF") },
                                    leadingIcon = { Icon(Icons.Default.WrapText, null) },
                                    onClick = {
                                        wordWrap = !wordWrap
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Recheck Syntax") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.manualRecheck()
                                    }
                                )
                            }
                        }
                    }
                )

                // Tab Bar
                ScrollableTabRow(
                    selectedTabIndex = currentIndex.coerceIn(0, maxOf(0, tabs.size - 1)),
                    edgePadding = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = currentIndex == index,
                            onClick = { viewModel.switchTab(index) },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (currentIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tab.name + if (tab.isModified) " *" else "",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close tab",
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { viewModel.closeTab(index) }
                                    )
                                }
                            }
                        )
                    }
                    IconButton(onClick = { viewModel.addTab() }) {
                        Icon(Icons.Default.Add, contentDescription = "New Tab", modifier = Modifier.size(20.dp))
                    }
                }

                // Quick Coding Symbols Toolbar
                val quickSymbols = listOf("    ", "{", "}", "(", ")", "[", "]", "\"", "'", ";", ":", "=", "+", "-", "*", "/", "<", ">", "->", "=>", "//")
                val symbolHScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .horizontalScroll(symbolHScroll)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    quickSymbols.forEach { sym ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    viewModel.insertSymbol(sym)
                                }
                        ) {
                            Text(
                                text = if (sym == "    ") "Tab ⇥" else sym,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Find & Replace Expandable Panel
                if (showFindReplace) {
                    Surface(
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { query ->
                                        searchQuery = query
                                        if (query.isNotEmpty()) {
                                            val regexStr = if (matchWord) "\\b${Regex.escape(query)}\\b" else Regex.escape(query)
                                            val regex = Regex(regexStr, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                                            searchMatchCount = regex.findAll(currentTab.content.text).count()
                                        } else {
                                            searchMatchCount = 0
                                        }
                                    },
                                    label = { Text("Find") },
                                    singleLine = true,
                                    trailingIcon = {
                                        if (searchMatchCount > 0) {
                                            Text(
                                                "$searchMatchCount found",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(Modifier.width(8.dp))

                                // Find Next
                                IconButton(onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        val text = currentTab.content.text
                                        var regexStr = Regex.escape(searchQuery)
                                        if (matchWord) regexStr = "\\b$regexStr\\b"
                                        val regex = Regex(regexStr, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

                                        val currentPos = currentTab.content.selection.end
                                        val nextMatch = regex.find(text, currentPos) ?: regex.find(text)
                                        if (nextMatch != null) {
                                            viewModel.updateContent(
                                                currentTab.content.copy(
                                                    selection = TextRange(nextMatch.range.first, nextMatch.range.last + 1)
                                                )
                                            )
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Find Next")
                                }

                                // Close panel
                                IconButton(onClick = { showFindReplace = false }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search")
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = replaceQuery,
                                    onValueChange = { replaceQuery = it },
                                    label = { Text("Replace with") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(Modifier.width(8.dp))

                                Button(
                                    onClick = {
                                        if (searchQuery.isNotEmpty()) {
                                            val text = currentTab.content.text
                                            var regexStr = Regex.escape(searchQuery)
                                            if (matchWord) regexStr = "\\b$regexStr\\b"
                                            val regex = Regex(regexStr, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

                                            val nextMatch = regex.find(text, currentTab.content.selection.start) ?: regex.find(text)
                                            if (nextMatch != null) {
                                                val newText = text.replaceRange(nextMatch.range, replaceQuery)
                                                viewModel.updateContent(
                                                    TextFieldValue(newText, TextRange(nextMatch.range.first + replaceQuery.length))
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Text("Replace")
                                }

                                Spacer(Modifier.width(4.dp))

                                OutlinedButton(
                                    onClick = {
                                        if (searchQuery.isNotEmpty()) {
                                            val text = currentTab.content.text
                                            var regexStr = Regex.escape(searchQuery)
                                            if (matchWord) regexStr = "\\b$regexStr\\b"
                                            val regex = Regex(regexStr, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

                                            val newText = text.replace(regex, replaceQuery)
                                            if (newText != text) {
                                                viewModel.updateContent(TextFieldValue(newText))
                                                Toast.makeText(context, "Replaced all occurrences", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                ) {
                                    Text("All")
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = !ignoreCase, onCheckedChange = { ignoreCase = !it })
                                    Text("Match Case", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = matchWord, onCheckedChange = { matchWord = it })
                                    Text("Whole Word", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            val errorCount = diagnosticIssues.count { it.isError }
            val warningCount = diagnosticIssues.count { !it.isError }
            
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Ln $currentLineNumber, Col $currentColumnNumber • $totalLines lines • $totalWords words",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = detectedLang,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "UTF-8",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Clickable diagnostic badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                errorCount > 0 -> MaterialTheme.colorScheme.errorContainer
                                warningCount > 0 -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showDiagnosticsSheet = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        errorCount > 0 -> Icons.Default.Error
                                        warningCount > 0 -> Icons.Default.Warning
                                        else -> Icons.Default.CheckCircle
                                    },
                                    contentDescription = "Diagnostics",
                                    modifier = Modifier.size(13.dp),
                                    tint = when {
                                        errorCount > 0 -> MaterialTheme.colorScheme.error
                                        warningCount > 0 -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = when {
                                        errorCount > 0 && warningCount > 0 -> "$errorCount 🔴 $warningCount 🟡"
                                        errorCount > 0 -> "$errorCount err"
                                        warningCount > 0 -> "$warningCount warn"
                                        else -> "Clean"
                                    },
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                    color = when {
                                        errorCount > 0 -> MaterialTheme.colorScheme.onErrorContainer
                                        warningCount > 0 -> MaterialTheme.colorScheme.onTertiaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()

        // MAIN EDITOR BODY: Unified vertical scroll on the parent Row completely resolves
        // the dual ScrollState attachment crash.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(vScroll)
        ) {
            // Line numbers column
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
                for (i in 1..totalLines) {
                    val lineHasError = diagnosticIssues.any { it.line == i && it.isError }
                    val lineHasWarning = !lineHasError && diagnosticIssues.any { it.line == i && !it.isError }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(vertical = 1.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .clickable {
                                viewModel.jumpToLine(i)
                            }
                    ) {
                        if (lineHasError) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(3.dp))
                            )
                            Spacer(Modifier.width(3.dp))
                        } else if (lineHasWarning) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFFE5A100), RoundedCornerShape(3.dp))
                            )
                            Spacer(Modifier.width(3.dp))
                        }

                        Text(
                            text = i.toString(),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            ),
                            color = if (i == currentLineNumber) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }

            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 1.dp
            )

            // Editor Text Field
            val editorModifier = if (wordWrap) {
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            } else {
                Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            }

            Box(modifier = editorModifier) {
                BasicTextField(
                    value = currentTab.content,
                    onValueChange = { newTextValue ->
                        val autoIndented = viewModel.handleTextChangeWithAutoIndent(newTextValue)
                        viewModel.updateContent(autoIndented)
                    },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = SyntaxHighlighter(
                        fileExtension = currentTab.file?.extension ?: currentTab.name.substringAfterLast('.', ""),
                        searchQuery = if (showFindReplace) searchQuery else ""
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Diagnostics Dialog / BottomSheet
        if (showDiagnosticsSheet) {
            AlertDialog(
                onDismissRequest = { showDiagnosticsSheet = false },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Diagnostics (${diagnosticIssues.size})")
                        IconButton(onClick = { viewModel.manualRecheck() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recheck")
                        }
                    }
                },
                text = {
                    if (diagnosticIssues.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 16.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("No syntax errors or warnings detected.")
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 350.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            diagnosticIssues.forEach { issue ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (issue.isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                        else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            viewModel.jumpToLine(issue.line)
                                            showDiagnosticsSheet = false
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(
                                            imageVector = if (issue.isError) Icons.Default.Error else Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = if (issue.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "Line ${issue.line}, Col ${issue.column}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (issue.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                                            )
                                            Text(
                                                text = issue.message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDiagnosticsSheet = false }) {
                        Text("Close")
                    }
                }
            )
        }

        // Go to line dialog
        if (showGoToLine) {
            var lineInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showGoToLine = false },
                title = { Text("Go to Line") },
                text = {
                    OutlinedTextField(
                        value = lineInput,
                        onValueChange = { lineInput = it },
                        label = { Text("Line number (1 - $totalLines)") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val lineNum = lineInput.toIntOrNull()
                        if (lineNum != null && lineNum > 0) {
                            val lines = currentTab.content.text.lines()
                            val targetLine = (lineNum - 1).coerceIn(0, maxOf(0, lines.size - 1))
                            var offset = 0
                            for (i in 0 until targetLine) {
                                offset += lines[i].length + 1
                            }
                            viewModel.updateContent(currentTab.content.copy(selection = TextRange(offset)))
                        }
                        showGoToLine = false
                    }) {
                        Text("Go")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGoToLine = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Save As Dialog
        if (showSaveAsDialog) {
            var saveAsName by remember { mutableStateOf(currentTab.name) }
            AlertDialog(
                onDismissRequest = { showSaveAsDialog = false },
                title = { Text("Save As") },
                text = {
                    OutlinedTextField(
                        value = saveAsName,
                        onValueChange = { saveAsName = it },
                        label = { Text("File name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val targetFolder = currentTab.file?.parentFile ?: filesDir
                        val ok = viewModel.saveFileAs(saveAsName, targetFolder)
                        if (ok) {
                            Toast.makeText(context, "Saved as $saveAsName", Toast.LENGTH_SHORT).show()
                            showSaveAsDialog = false
                        } else {
                            Toast.makeText(context, "Failed to save file", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveAsDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Unsaved changes confirmation dialog
        if (showUnsavedDialog) {
            AlertDialog(
                onDismissRequest = { showUnsavedDialog = false },
                icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Unsaved Changes") },
                text = { Text("You have unsaved changes in your tabs. Do you want to save before leaving?") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.saveCurrentFile(filesDir)
                        showUnsavedDialog = false
                        onBack()
                    }) {
                        Text("Save & Exit")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            showUnsavedDialog = false
                            onBack()
                        }) {
                            Text("Discard & Exit", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { showUnsavedDialog = false }) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }
    }
}
