package com.example.ui.screens.renamer

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RenameMode {
    PREFIX_SUFFIX,
    FIND_REPLACE,
    NUMBERING,
    CASE_CHANGE,
    EXTENSION
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchRenamerScreen(
    initialFilePaths: List<String> = emptyList(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var files by remember {
        mutableStateOf(initialFilePaths.map { File(it) }.filter { it.exists() })
    }

    var selectedMode by remember { mutableStateOf(RenameMode.PREFIX_SUFFIX) }

    // Mode options
    var prefixText by remember { mutableStateOf("") }
    var suffixText by remember { mutableStateOf("") }

    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }

    var numberPrefix by remember { mutableStateOf("file_") }
    var numberStart by remember { mutableIntStateOf(1) }
    var zeroPadding by remember { mutableIntStateOf(2) } // 2 digits: 01, 02

    var caseOption by remember { mutableIntStateOf(0) } // 0: lowercase, 1: UPPERCASE, 2: Title Case

    var newExtension by remember { mutableStateOf("") }

    var isApplying by remember { mutableStateOf(false) }

    // Multi-file picker if empty
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            Toast.makeText(context, "${uris.size} files selected. Note: Best used directly from File Manager selection.", Toast.LENGTH_LONG).show()
        }
    }

    // Compute preview of new names
    fun computeNewName(file: File, index: Int): String {
        val baseName = file.nameWithoutExtension
        val ext = file.extension

        return when (selectedMode) {
            RenameMode.PREFIX_SUFFIX -> {
                val newBase = "$prefixText$baseName$suffixText"
                if (ext.isNotEmpty()) "$newBase.$ext" else newBase
            }
            RenameMode.FIND_REPLACE -> {
                if (findText.isEmpty()) file.name else {
                    val newName = file.name.replace(findText, replaceText)
                    newName
                }
            }
            RenameMode.NUMBERING -> {
                val num = numberStart + index
                val numStr = String.format("%0${zeroPadding}d", num)
                val newBase = "$numberPrefix$numStr"
                if (ext.isNotEmpty()) "$newBase.$ext" else newBase
            }
            RenameMode.CASE_CHANGE -> {
                val transformedBase = when (caseOption) {
                    0 -> baseName.lowercase()
                    1 -> baseName.uppercase()
                    else -> baseName.split(" ", "_", "-").joinToString(" ") { word ->
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                }
                if (ext.isNotEmpty()) "$transformedBase.$ext" else transformedBase
            }
            RenameMode.EXTENSION -> {
                val cleanExt = newExtension.removePrefix(".")
                if (cleanExt.isEmpty()) file.name else "$baseName.$cleanExt"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Batch File Renamer (${files.size} files)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (files.isNotEmpty()) {
                        IconButton(onClick = { files = emptyList() }) {
                            Icon(Icons.Default.ClearAll, contentDescription = "Clear Selection")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (files.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 6.dp
                ) {
                    Button(
                        onClick = {
                            isApplying = true
                            scope.launch(Dispatchers.IO) {
                                var successCount = 0
                                var failCount = 0
                                val newFileList = mutableListOf<File>()

                                files.forEachIndexed { index, file ->
                                    val newName = computeNewName(file, index)
                                    if (newName != file.name) {
                                        val dest = File(file.parentFile, newName)
                                        if (file.renameTo(dest)) {
                                            successCount++
                                            newFileList.add(dest)
                                        } else {
                                            failCount++
                                            newFileList.add(file)
                                        }
                                    } else {
                                        newFileList.add(file)
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    files = newFileList
                                    isApplying = false
                                    Toast.makeText(
                                        context,
                                        "Renamed $successCount file(s)" + if (failCount > 0) ", $failCount failed" else "",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = !isApplying,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (isApplying) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Renaming...")
                        } else {
                            Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Apply Batch Rename (${files.size} Files)")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DriveFileRenameOutline,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "No Files Selected",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Select multiple files in DevVault File Manager, then choose 'Batch Rename' from the action menu, or select files here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { multiPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pick Files to Rename")
                        }
                    }
                }
            } else {
                // Mode Selector Tabs
                ScrollableTabRow(
                    selectedTabIndex = selectedMode.ordinal,
                    edgePadding = 0.dp
                ) {
                    Tab(
                        selected = selectedMode == RenameMode.PREFIX_SUFFIX,
                        onClick = { selectedMode = RenameMode.PREFIX_SUFFIX },
                        text = { Text("Prefix/Suffix") }
                    )
                    Tab(
                        selected = selectedMode == RenameMode.FIND_REPLACE,
                        onClick = { selectedMode = RenameMode.FIND_REPLACE },
                        text = { Text("Find & Replace") }
                    )
                    Tab(
                        selected = selectedMode == RenameMode.NUMBERING,
                        onClick = { selectedMode = RenameMode.NUMBERING },
                        text = { Text("Numbering") }
                    )
                    Tab(
                        selected = selectedMode == RenameMode.CASE_CHANGE,
                        onClick = { selectedMode = RenameMode.CASE_CHANGE },
                        text = { Text("Case") }
                    )
                    Tab(
                        selected = selectedMode == RenameMode.EXTENSION,
                        onClick = { selectedMode = RenameMode.EXTENSION },
                        text = { Text("Extension") }
                    )
                }

                // Mode Configuration Inputs
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (selectedMode) {
                            RenameMode.PREFIX_SUFFIX -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = prefixText,
                                        onValueChange = { prefixText = it },
                                        label = { Text("Prefix (start)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = suffixText,
                                        onValueChange = { suffixText = it },
                                        label = { Text("Suffix (end)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                            }
                            RenameMode.FIND_REPLACE -> {
                                OutlinedTextField(
                                    value = findText,
                                    onValueChange = { findText = it },
                                    label = { Text("Find text") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = replaceText,
                                    onValueChange = { replaceText = it },
                                    label = { Text("Replace with") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                            RenameMode.NUMBERING -> {
                                OutlinedTextField(
                                    value = numberPrefix,
                                    onValueChange = { numberPrefix = it },
                                    label = { Text("Base Name Prefix") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = numberStart.toString(),
                                        onValueChange = { numberStart = it.toIntOrNull() ?: 1 },
                                        label = { Text("Start Number") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = zeroPadding.toString(),
                                        onValueChange = { zeroPadding = it.toIntOrNull() ?: 2 },
                                        label = { Text("Zero Padding (digits)") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                            }
                            RenameMode.CASE_CHANGE -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = caseOption == 0,
                                        onClick = { caseOption = 0 },
                                        label = { Text("lowercase") }
                                    )
                                    FilterChip(
                                        selected = caseOption == 1,
                                        onClick = { caseOption = 1 },
                                        label = { Text("UPPERCASE") }
                                    )
                                    FilterChip(
                                        selected = caseOption == 2,
                                        onClick = { caseOption = 2 },
                                        label = { Text("Title Case") }
                                    )
                                }
                            }
                            RenameMode.EXTENSION -> {
                                OutlinedTextField(
                                    value = newExtension,
                                    onValueChange = { newExtension = it },
                                    label = { Text("New Extension (e.g. jpg, txt, bin)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                // Live Preview List
                Text(
                    text = "Live Rename Preview (${files.size} items)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(files) { index, file ->
                        val newName = computeNewName(file, index)
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.outline
                                    ),
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = newName,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (newName != file.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
