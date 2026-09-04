package com.example.ui.screens.hex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import java.io.RandomAccessFile

data class HexRow(
    val offset: Long,
    val hexString: String,
    val asciiString: String,
    val rawBytes: ByteArray
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HexViewerScreen(
    initialFilePath: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var currentFile by remember { mutableStateOf<File?>(initialFilePath?.let { File(it).takeIf { f -> f.exists() } }) }
    var rows by remember { mutableStateOf<List<HexRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var currentOffset by remember { mutableLongStateOf(0L) }
    var totalFileSize by remember { mutableLongStateOf(0L) }
    val chunkSize = 4096 // 4 KB per page (256 rows of 16 bytes)

    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var isHexSearch by remember { mutableStateOf(false) }

    fun loadChunk(file: File, offset: Long) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val fileSize = file.length()
                totalFileSize = fileSize
                val actualOffset = offset.coerceIn(0L, (fileSize - 1).coerceAtLeast(0L))
                val bytesToRead = chunkSize.toLong().coerceAtMost(fileSize - actualOffset).toInt()
                val buffer = ByteArray(bytesToRead)

                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(actualOffset)
                    raf.readFully(buffer)
                }

                val parsedRows = mutableListOf<HexRow>()
                for (i in 0 until bytesToRead step 16) {
                    val rowLength = 16.coerceAtMost(bytesToRead - i)
                    val rowBytes = buffer.copyOfRange(i, i + rowLength)
                    val rowOffset = actualOffset + i

                    val hexSb = StringBuilder()
                    val asciiSb = StringBuilder()
                    for (b in rowBytes) {
                        hexSb.append(String.format("%02X ", b))
                        val c = (b.toInt() and 0xFF).toChar()
                        if (c in ' '..'~') {
                            asciiSb.append(c)
                        } else {
                            asciiSb.append('.')
                        }
                    }

                    // Pad hex if less than 16 bytes
                    while (hexSb.length < 48) {
                        hexSb.append("   ")
                    }

                    parsedRows.add(
                        HexRow(
                            offset = rowOffset,
                            hexString = hexSb.toString(),
                            asciiString = asciiSb.toString(),
                            rawBytes = rowBytes
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    rows = parsedRows
                    currentOffset = actualOffset
                    isLoading = false
                    listState.scrollToItem(0)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(currentFile) {
        currentFile?.let { loadChunk(it, 0L) }
    }

    // Document Picker for opening another file
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                // Copy to cache or resolve path
                val tempFile = File(context.cacheDir, "hex_inspect_${System.currentTimeMillis()}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                currentFile = tempFile
            } catch (e: Exception) {
                Toast.makeText(context, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(currentFile?.name ?: "Hex & Binary Inspector", maxLines = 1)
                        if (currentFile != null) {
                            Text(
                                "Size: ${Formatter.formatShortFileSize(context, totalFileSize)} • Offset: 0x${java.lang.Long.toHexString(currentOffset).uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Open File")
                    }
                    if (currentFile != null) {
                        IconButton(onClick = { showJumpDialog = true }) {
                            Icon(Icons.Default.NearMe, contentDescription = "Jump to Offset")
                        }
                        IconButton(onClick = { showSearchDialog = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search Bytes")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (currentFile != null && totalFileSize > chunkSize) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val prev = (currentOffset - chunkSize).coerceAtLeast(0L)
                                currentFile?.let { loadChunk(it, prev) }
                            },
                            enabled = currentOffset > 0 && !isLoading
                        ) {
                            Icon(Icons.Default.NavigateBefore, contentDescription = null)
                            Text("Prev Page")
                        }

                        Text(
                            "0x${java.lang.Long.toHexString(currentOffset).uppercase()} - 0x${java.lang.Long.toHexString((currentOffset + chunkSize).coerceAtMost(totalFileSize)).uppercase()}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                        )

                        FilledTonalButton(
                            onClick = {
                                val next = currentOffset + chunkSize
                                if (next < totalFileSize) {
                                    currentFile?.let { loadChunk(it, next) }
                                }
                            },
                            enabled = currentOffset + chunkSize < totalFileSize && !isLoading
                        ) {
                            Text("Next Page")
                            Icon(Icons.Default.NavigateNext, contentDescription = null)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (currentFile == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DataArray,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No file selected",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open any binary file, APK, ELF library, database, or media file to inspect its raw hexadecimal bytes and ASCII structure.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Pick File to Inspect")
                    }
                }
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Bar
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "OFFSET    ",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                "00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F  ",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            )
                            Text(
                                "ASCII",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            )
                        }
                    }

                    // Hex Rows List
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        itemsIndexed(rows) { index, row ->
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Offset
                                Text(
                                    text = String.format("%08X: ", row.offset),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 12.sp
                                    )
                                )

                                // Hex Bytes
                                Text(
                                    text = row.hexString,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.sp
                                    )
                                )

                                Spacer(Modifier.width(8.dp))

                                // ASCII string
                                Text(
                                    text = row.asciiString,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Jump to Offset Dialog
        if (showJumpDialog) {
            AlertDialog(
                onDismissRequest = { showJumpDialog = false },
                title = { Text("Jump to Offset") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter hex offset (e.g. 0x100 or 100) or decimal byte number:", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = jumpInput,
                            onValueChange = { jumpInput = it },
                            placeholder = { Text("0x0000 or 1024") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val clean = jumpInput.trim()
                            val targetOffset = try {
                                if (clean.startsWith("0x", ignoreCase = true)) {
                                    clean.substring(2).toLong(16)
                                } else {
                                    clean.toLong()
                                }
                            } catch (e: Exception) {
                                null
                            }
                            if (targetOffset != null && currentFile != null) {
                                loadChunk(currentFile!!, (targetOffset / 16) * 16)
                                showJumpDialog = false
                            } else {
                                Toast.makeText(context, "Invalid offset value", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Jump")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showJumpDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Search Bytes / Text Dialog
        if (showSearchDialog) {
            AlertDialog(
                onDismissRequest = { showSearchDialog = false },
                title = { Text("Search Binary Data") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = !isHexSearch,
                                onClick = { isHexSearch = false },
                                label = { Text("ASCII String") }
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = isHexSearch,
                                onClick = { isHexSearch = true },
                                label = { Text("Hex Bytes (e.g. 7F 45)") }
                            )
                        }
                        OutlinedTextField(
                            value = searchInput,
                            onValueChange = { searchInput = it },
                            placeholder = { Text(if (isHexSearch) "50 4B 03 04" else "search term...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val term = searchInput.trim()
                            if (term.isEmpty() || currentFile == null) return@TextButton
                            showSearchDialog = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val searchBytes = if (isHexSearch) {
                                        term.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                    } else {
                                        term.toByteArray(Charsets.UTF_8)
                                    }

                                    val file = currentFile!!
                                    val buf = ByteArray(65536)
                                    var foundOffset: Long? = null
                                    RandomAccessFile(file, "r").use { raf ->
                                        var pos = 0L
                                        while (pos < file.length()) {
                                            raf.seek(pos)
                                            val read = raf.read(buf)
                                            if (read <= 0) break
                                            val idx = indexOfSubarray(buf, read, searchBytes)
                                            if (idx != -1) {
                                                foundOffset = pos + idx
                                                break
                                            }
                                            pos += (read - searchBytes.size).coerceAtLeast(1)
                                        }
                                    }

                                    withContext(Dispatchers.Main) {
                                        if (foundOffset != null) {
                                            Toast.makeText(context, "Found at offset 0x${java.lang.Long.toHexString(foundOffset).uppercase()}", Toast.LENGTH_LONG).show()
                                            loadChunk(file, (foundOffset / 16) * 16)
                                        } else {
                                            Toast.makeText(context, "Pattern not found in file", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Search")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSearchDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

private fun indexOfSubarray(array: ByteArray, arrayLength: Int, target: ByteArray): Int {
    if (target.isEmpty() || target.size > arrayLength) return -1
    for (i in 0..arrayLength - target.size) {
        var match = true
        for (j in target.indices) {
            if (array[i + j] != target[j]) {
                match = false
                break
            }
        }
        if (match) return i
    }
    return -1
}
