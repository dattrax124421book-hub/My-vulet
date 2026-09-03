package com.example.ui.screens.apk

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ApkItem(val name: String, val packageName: String, val icon: android.graphics.drawable.Drawable, val sourceDir: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    
    var apps by remember { mutableStateOf<List<ApkItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map {
                    ApkItem(
                        name = packageManager.getApplicationLabel(it).toString(),
                        packageName = it.packageName,
                        icon = packageManager.getApplicationIcon(it),
                        sourceDir = it.sourceDir
                    )
                }
                .sortedBy { it.name.lowercase() }
            
            withContext(Dispatchers.Main) {
                apps = installedApps
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("APK Extractor") })
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(apps) { app ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Image(
                                    bitmap = app.icon.toBitmap().asImageBitmap(),
                                    contentDescription = app.name,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(app.name, style = MaterialTheme.typography.titleMedium)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            IconButton(onClick = {
                                val sourceFile = File(app.sourceDir)
                                val destFile = File(context.filesDir, "${app.packageName}.apk")
                                try {
                                    sourceFile.copyTo(destFile, overwrite = true)
                                    Toast.makeText(context, "Saved to ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(context, "Failed to extract", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.Download, contentDescription = "Extract APK")
                            }
                        }
                    }
                }
            }
        }
    }
}
