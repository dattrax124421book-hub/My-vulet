package com.example.ui.screens.hash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.CRC32

data class HashResult(
    val algorithm: String,
    val value: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashCalculatorScreen(
    initialFilePath: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFile by remember { mutableStateOf<File?>(initialFilePath?.let { File(it).takeIf { f -> f.exists() } }) }
    var textInput by remember { mutableStateOf("") }
    var isTextMode by remember { mutableStateOf(false) }

    var isCalculating by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var results by remember { mutableStateOf<List<HashResult>>(emptyList()) }

    var compareHashInput by remember { mutableStateOf("") }

    fun calculateHashes(file: File) {
        isCalculating = true
        progress = 0f
        results = emptyList()

        scope.launch(Dispatchers.IO) {
            try {
                val totalBytes = file.length()
                val md5 = MessageDigest.getInstance("MD5")
                val sha1 = MessageDigest.getInstance("SHA-1")
                val sha256 = MessageDigest.getInstance("SHA-256")
                val sha512 = MessageDigest.getInstance("SHA-512")
                val crc = CRC32()

                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(65536) // 64 KB chunks
                    var bytesRead: Int
                    var totalRead = 0L

                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        md5.update(buffer, 0, bytesRead)
                        sha1.update(buffer, 0, bytesRead)
                        sha256.update(buffer, 0, bytesRead)
                        sha512.update(buffer, 0, bytesRead)
                        crc.update(buffer, 0, bytesRead)

                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            withContext(Dispatchers.Main) {
                                progress = totalRead.toFloat() / totalBytes.toFloat()
                            }
                        }
                    }
                }

                val calculated = listOf(
                    HashResult("CRC32", String.format("%08X", crc.value)),
                    HashResult("MD5", md5.digest().joinToString("") { "%02x".format(it) }),
                    HashResult("SHA-1", sha1.digest().joinToString("") { "%02x".format(it) }),
                    HashResult("SHA-256", sha256.digest().joinToString("") { "%02x".format(it) }),
                    HashResult("SHA-512", sha512.digest().joinToString("") { "%02x".format(it) })
                )

                withContext(Dispatchers.Main) {
                    results = calculated
                    isCalculating = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCalculating = false
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun calculateTextHashes(text: String) {
        if (text.isEmpty()) return
        isCalculating = true
        scope.launch(Dispatchers.Default) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val md5 = MessageDigest.getInstance("MD5").digest(bytes)
            val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sha512 = MessageDigest.getInstance("SHA-512").digest(bytes)
            val crc = CRC32().apply { update(bytes) }

            val calculated = listOf(
                HashResult("CRC32", String.format("%08X", crc.value)),
                HashResult("MD5", md5.joinToString("") { "%02x".format(it) }),
                HashResult("SHA-1", sha1.joinToString("") { "%02x".format(it) }),
                HashResult("SHA-256", sha256.joinToString("") { "%02x".format(it) }),
                HashResult("SHA-512", sha512.joinToString("") { "%02x".format(it) })
            )
            withContext(Dispatchers.Main) {
                results = calculated
                isCalculating = false
            }
        }
    }

    LaunchedEffect(selectedFile) {
        selectedFile?.let { calculateHashes(it) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val tempFile = File(context.cacheDir, "hash_target_${System.currentTimeMillis()}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                selectedFile = tempFile
                isTextMode = false
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checksum & Hash Generator") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Pick File")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mode Switch
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    FilterChip(
                        selected = !isTextMode,
                        onClick = { isTextMode = false },
                        label = { Text("File Mode") },
                        leadingIcon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Spacer(Modifier.width(12.dp))
                    FilterChip(
                        selected = isTextMode,
                        onClick = { isTextMode = true },
                        label = { Text("Plain Text Mode") },
                        leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            if (!isTextMode) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = selectedFile?.name ?: "No file chosen",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1
                                    )
                                    if (selectedFile != null) {
                                        Text(
                                            text = "Size: ${Formatter.formatShortFileSize(context, selectedFile!!.length())}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { filePicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Select File")
                                }

                                if (selectedFile != null) {
                                    OutlinedButton(
                                        onClick = { calculateHashes(selectedFile!!) },
                                        enabled = !isCalculating
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Re-calculate")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                label = { Text("Enter text to hash") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { calculateTextHashes(textInput) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = textInput.isNotEmpty() && !isCalculating
                            ) {
                                Icon(Icons.Default.Calculate, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Generate Text Hashes")
                            }
                        }
                    }
                }
            }

            // Calculation Progress
            if (isCalculating) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Computing Cryptographic Hashes...", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Hash Comparison Section
            if (results.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Hash Verification / Comparison", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            OutlinedTextField(
                                value = compareHashInput,
                                onValueChange = { compareHashInput = it.trim() },
                                placeholder = { Text("Paste expected hash here to verify...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (compareHashInput.isNotEmpty()) {
                                val matched = results.firstOrNull { it.value.equals(compareHashInput, ignoreCase = true) }
                                if (matched != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF166534).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                            .padding(10.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF22C55E))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "MATCH VERIFIED! Perfectly matches ${matched.algorithm}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF22C55E))
                                        )
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF991B1B).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                            .padding(10.dp)
                                    ) {
                                        Icon(Icons.Default.Cancel, contentDescription = null, tint = Color(0xFFEF4444))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "NO MATCH. Checksum does not match any computed hash.",
                                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFEF4444))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Results List
            if (results.isNotEmpty()) {
                item {
                    Text("Computed Checksums", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }

                items(results.size) { idx ->
                    val item = results[idx]
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.algorithm,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                )
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(item.algorithm, item.value))
                                        Toast.makeText(context, "${item.algorithm} copied", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Hash", modifier = Modifier.size(18.dp))
                                }
                            }
                            Text(
                                text = item.value,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
