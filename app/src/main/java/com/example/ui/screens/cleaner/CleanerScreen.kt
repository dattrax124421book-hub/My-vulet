package com.example.ui.screens.cleaner

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    
    var totalSpace by remember { mutableStateOf(0L) }
    var freeSpace by remember { mutableStateOf(0L) }
    var appDataSpace by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        
        totalSpace = totalBlocks * blockSize
        freeSpace = availableBlocks * blockSize
        
        fun getDirSize(dir: java.io.File): Long {
            var size = 0L
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { child ->
                    size += getDirSize(child)
                }
            } else {
                size = dir.length()
            }
            return size
        }
        
        appDataSpace = getDirSize(context.filesDir)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Storage Analyzer") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Device Storage", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Total: ${formatSize(totalSpace)}")
                        Text("Free: ${formatSize(freeSpace)}")
                        Text("Used: ${formatSize(totalSpace - freeSpace)}")
                    }
                }
            }
            
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("App Data Storage", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Files Directory: ${formatSize(appDataSpace)}")
                    }
                }
            }
        }
    }
}

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
