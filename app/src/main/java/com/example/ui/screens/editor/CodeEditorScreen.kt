package com.example.ui.screens.editor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(onBack: () -> Unit, filesDir: File, initialFilePath: String? = null) {
    val viewModel: CodeEditorViewModel = viewModel()
    val tabs by viewModel.tabs.collectAsState()
    val currentIndex by viewModel.currentTabIndex.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val context = LocalContext.current
    
    var showFindReplace by remember { mutableStateOf(false) }
    var showGoToLine by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var ignoreCase by remember { mutableStateOf(true) }
    var matchWord by remember { mutableStateOf(false) }
    
    LaunchedEffect(initialFilePath) {
        if (initialFilePath != null) {
            val file = File(initialFilePath)
            if (file.exists() && tabs.none { it.file?.absolutePath == file.absolutePath }) {
                viewModel.addTab(file)
            }
        }
    }
    
    val currentTab = tabs.getOrNull(currentIndex) ?: return
    
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Code Editor") },
                    actions = {
                        IconButton(onClick = { showFindReplace = !showFindReplace }) {
                            Icon(Icons.Default.Search, contentDescription = "Find/Replace")
                        }
                        IconButton(onClick = { showGoToLine = true }) {
                            Icon(Icons.Default.Numbers, contentDescription = "Go to Line")
                        }
                        IconButton(onClick = { viewModel.undo() }) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = { viewModel.redo() }) {
                            Icon(Icons.Default.Redo, contentDescription = "Redo")
                        }
                        IconButton(onClick = { 
                            if (viewModel.saveCurrentFile(filesDir)) {
                                Toast.makeText(context, "Saved successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to save", Toast.LENGTH_LONG).show()
                            }
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "Save File")
                        }
                    }
                )
                // Tabs Row
                ScrollableTabRow(
                    selectedTabIndex = currentIndex,
                    edgePadding = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = currentIndex == index,
                            onClick = { viewModel.switchTab(index) },
                            text = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = tab.name + if (tab.isModified) "*" else "")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.Close, 
                                        contentDescription = "Close",
                                        modifier = Modifier.size(16.dp).clickable { viewModel.closeTab(index) }
                                    )
                                }
                            }
                        )
                    }
                    IconButton(onClick = { viewModel.addTab() }) {
                        Icon(Icons.Default.Add, contentDescription = "New Tab")
                    }
                }
                
                if (showFindReplace) {
                    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                label = { Text("Find") },
                                modifier = Modifier.weight(1f).height(56.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = !ignoreCase, onCheckedChange = { ignoreCase = !it })
                                Text("Aa", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.width(4.dp))
                                Checkbox(checked = matchWord, onCheckedChange = { matchWord = it })
                                Text("Word", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = replaceQuery,
                                onValueChange = { replaceQuery = it },
                                label = { Text("Replace") },
                                modifier = Modifier.weight(1f).height(56.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                if (searchQuery.isNotEmpty()) {
                                    val text = currentTab.content.text
                                    var regexStr = Regex.escape(searchQuery)
                                    if (matchWord) regexStr = "\\b$regexStr\\b"
                                    val regex = Regex(regexStr, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                                    
                                    val nextMatch = regex.find(text, currentTab.content.selection.end) ?: regex.find(text)
                                    if (nextMatch != null) {
                                        val newContent = text.replaceRange(nextMatch.range, replaceQuery)
                                        viewModel.updateContent(TextFieldValue(newContent, TextRange(nextMatch.range.first + replaceQuery.length)))
                                    }
                                }
                            }) { Text("Replace") }
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(onClick = {
                                if (searchQuery.isNotEmpty()) {
                                    val text = currentTab.content.text
                                    var regexStr = Regex.escape(searchQuery)
                                    if (matchWord) regexStr = "\\b$regexStr\\b"
                                    val regex = Regex(regexStr, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                                    
                                    val newContent = text.replace(regex, replaceQuery)
                                    if (newContent != text) {
                                        viewModel.updateContent(TextFieldValue(newContent))
                                    }
                                }
                            }) { Text("All") }
                        }
                    }
                }
            }
        },
        bottomBar = {
            BottomAppBar(modifier = Modifier.height(56.dp)) {
                Text(
                    text = "Diagnostics: $diagnostics", 
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    ) { padding ->
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        
        Row(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            // Line numbers
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(vScroll)
                    .padding(8.dp)
            ) {
                val lineCount = currentTab.content.text.count { it == '\n' } + 1
                for (i in 1..lineCount) {
                    Text(
                        text = i.toString(),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            
            // Editor
            BasicTextField(
                value = currentTab.content,
                onValueChange = { viewModel.updateContent(it) },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace, 
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                visualTransformation = SyntaxHighlighter(currentTab.file?.extension ?: currentTab.name.substringAfterLast('.', ""), if (showFindReplace) searchQuery else ""),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
                    .padding(8.dp)
            )
        }
        
        if (showGoToLine) {
            var lineInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showGoToLine = false },
                title = { Text("Go to Line") },
                text = {
                    OutlinedTextField(
                        value = lineInput,
                        onValueChange = { lineInput = it },
                        label = { Text("Line number") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val lineNum = lineInput.toIntOrNull()
                        if (lineNum != null && lineNum > 0) {
                            val lines = currentTab.content.text.lines()
                            val targetLine = minOf(lineNum - 1, lines.size - 1)
                            var offset = 0
                            for (i in 0 until targetLine) {
                                offset += lines[i].length + 1
                            }
                            viewModel.updateContent(currentTab.content.copy(selection = TextRange(offset)))
                        }
                        showGoToLine = false
                    }) { Text("Go") }
                },
                dismissButton = {
                    TextButton(onClick = { showGoToLine = false }) { Text("Cancel") }
                }
            )
        }
    }
}
