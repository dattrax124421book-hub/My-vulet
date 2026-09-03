package com.example.ui.screens.apk

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.documentfile.provider.DocumentFile
import com.example.data.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.text.format.Formatter

data class ApkItem(
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: android.graphics.drawable.Drawable,
    val baseSourceDir: String,
    val splitSourceDirs: List<String>?,
    val isSystem: Boolean,
    val totalSize: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val scope = rememberCoroutineScope()
    val prefsRepo = remember { UserPreferencesRepository(context) }

    var apps by remember { mutableStateOf<List<ApkItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showSystemApps by remember { mutableStateOf(false) }
    
    var extractingApp by remember { mutableStateOf<String?>(null) }
    var extractingProgress by remember { mutableStateOf<Float?>(null) }

    val loadApps = {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val mapped = installedApps.mapNotNull { appInfo ->
                if (!showSystemApps && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
                
                try {
                    val packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0)
                    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                    val versionName = packageInfo.versionName ?: "N/A"
                    
                    val baseFile = File(appInfo.sourceDir)
                    var totalSize = baseFile.length()
                    val splits = appInfo.splitSourceDirs?.toList()
                    splits?.forEach { splitPath ->
                        totalSize += File(splitPath).length()
                    }
                    
                    ApkItem(
                        name = packageManager.getApplicationLabel(appInfo).toString(),
                        packageName = appInfo.packageName,
                        versionName = versionName,
                        versionCode = versionCode,
                        icon = packageManager.getApplicationIcon(appInfo),
                        baseSourceDir = appInfo.sourceDir,
                        splitSourceDirs = splits,
                        isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        totalSize = totalSize
                    )
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                apps = mapped
                isLoading = false
            }
        }
    }

    LaunchedEffect(showSystemApps) {
        loadApps()
    }

    var pendingExtraction by remember { mutableStateOf<ApkItem?>(null) }

    val safPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            scope.launch {
                prefsRepo.updateDefaultExportUri(uri.toString())
                pendingExtraction?.let { app ->
                    extractApk(context, app, uri.toString(), scope, { extractingApp = it }, { extractingProgress = it })
                }
                pendingExtraction = null
            }
        } else {
            pendingExtraction = null
            Toast.makeText(context, "Export cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APK Extractor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("System", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            )
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(apps) { app ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    bitmap = app.icon.toBitmap().asImageBitmap(),
                                    contentDescription = app.name,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.name, style = MaterialTheme.typography.titleMedium)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("v${app.versionName} (${app.versionCode})", style = MaterialTheme.typography.bodySmall)
                                        Text(Formatter.formatShortFileSize(context, app.totalSize), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            
                            if (app.splitSourceDirs != null && app.splitSourceDirs.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Contains ${app.splitSourceDirs.size + 1} files (base + splits)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            
                            if (extractingApp == app.packageName) {
                                Spacer(modifier = Modifier.height(16.dp))
                                val p = extractingProgress
                                if (p != null) {
                                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Text("Extracting...", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                            } else {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val prefs = prefsRepo.userPreferencesFlow.first()
                                            val exportUri = prefs.defaultExportUri
                                            if (exportUri.isNotEmpty()) {
                                                extractApk(context, app, exportUri, scope, { extractingApp = it }, { extractingProgress = it })
                                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                extractApk(context, app, null, scope, { extractingApp = it }, { extractingProgress = it })
                                            } else {
                                                pendingExtraction = app
                                                safPickerLauncher.launch(null)
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, contentDescription = "Extract APK")
                                        Spacer(Modifier.width(4.dp))
                                        Text("Extract All")
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

private suspend fun extractApk(
    context: Context,
    app: ApkItem,
    safUri: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    setExtracting: (String?) -> Unit,
    setProgress: (Float?) -> Unit
) {
    setExtracting(app.packageName)
    setProgress(0f)
    
    withContext(Dispatchers.IO) {
        try {
            val filesToExtract = mutableListOf<File>()
            filesToExtract.add(File(app.baseSourceDir))
            app.splitSourceDirs?.forEach { filesToExtract.add(File(it)) }
            
            var totalBytesToCopy = app.totalSize
            var bytesCopied = 0L
            
            val savedUris = mutableListOf<Uri>()
            val resolver = context.contentResolver

            for (file in filesToExtract) {
                val isBase = file.absolutePath == app.baseSourceDir
                val suffix = if (isBase) "base" else file.name
                val fileName = "${app.packageName}_${app.versionCode}_$suffix.apk"
                
                var outUri: Uri? = null
                var outputStream: java.io.OutputStream? = null
                
                if (!safUri.isNullOrEmpty()) {
                    val dir = DocumentFile.fromTreeUri(context, Uri.parse(safUri))
                    if (dir != null) {
                        val existing = dir.findFile(fileName)
                        existing?.delete()
                        val newFile = dir.createFile("application/vnd.android.package-archive", fileName)
                        if (newFile != null) {
                            outUri = newFile.uri
                            outputStream = resolver.openOutputStream(outUri)
                        } else {
                            throw Exception("Failed to create file in SAF directory")
                        }
                    } else {
                        throw Exception("Export directory not found or permission denied")
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DevVault")
                    }
                    outUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (outUri != null) {
                        outputStream = resolver.openOutputStream(outUri)
                    } else {
                        throw Exception("Failed to create MediaStore entry")
                    }
                } else {
                    throw Exception("No valid export location")
                }
                
                if (outputStream == null) throw Exception("Could not open output stream")
                
                file.inputStream().use { input ->
                    outputStream.use { out ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            bytesCopied += read
                            withContext(Dispatchers.Main) {
                                setProgress(bytesCopied.toFloat() / totalBytesToCopy.toFloat())
                            }
                        }
                    }
                }
                
                if (outUri != null) {
                    savedUris.add(outUri)
                }
            }
            
            withContext(Dispatchers.Main) {
                val pathMsg = if (!safUri.isNullOrEmpty()) "Export Folder" else "Downloads/DevVault"
                Toast.makeText(context, "Extracted ${savedUris.size} file(s) to $pathMsg", Toast.LENGTH_LONG).show()
                if (savedUris.size == 1) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, savedUris.first())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share APK"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            withContext(Dispatchers.Main) {
                setExtracting(null)
                setProgress(null)
            }
        }
    }
}
